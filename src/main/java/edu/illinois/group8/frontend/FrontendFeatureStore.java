package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.FeatureOutput;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class FrontendFeatureStore {
    public static final String BBO_FEATURE = "feature.bbo";

    private final int maxFeaturesPerMarket;
    private final int maxSymbolsIndexed;
    private final Map<String, Deque<FeatureOutput>> byMarket = new LinkedHashMap<>();
    private final Map<String, Map<String, FeatureOutput>> latestByMarket = new HashMap<>();
    private final AtomicLong totalAccepted = new AtomicLong();
    private long sequence;

    public FrontendFeatureStore(int maxFeaturesPerMarket, int maxSymbolsIndexed) {
        if (maxFeaturesPerMarket < 1) {
            throw new IllegalArgumentException("maxFeaturesPerMarket must be positive");
        }
        if (maxSymbolsIndexed < 1) {
            throw new IllegalArgumentException("maxSymbolsIndexed must be positive");
        }
        this.maxFeaturesPerMarket = maxFeaturesPerMarket;
        this.maxSymbolsIndexed = maxSymbolsIndexed;
    }

    public synchronized void accept(FeatureOutput out) {
        if (out == null) {
            return;
        }
        String market = out.marketTicker();
        if (market == null || market.isBlank()) {
            return;
        }
        Deque<FeatureOutput> deque = byMarket.get(market);
        if (deque == null) {
            if (byMarket.size() >= maxSymbolsIndexed) {
                Iterator<Map.Entry<String, Deque<FeatureOutput>>> it = byMarket.entrySet().iterator();
                if (it.hasNext()) {
                    Map.Entry<String, Deque<FeatureOutput>> evicted = it.next();
                    it.remove();
                    latestByMarket.remove(evicted.getKey());
                }
            }
            deque = new ArrayDeque<>(Math.min(maxFeaturesPerMarket, 1024));
            byMarket.put(market, deque);
        }
        deque.addLast(out);
        while (deque.size() > maxFeaturesPerMarket) {
            deque.removeFirst();
        }
        latestByMarket
            .computeIfAbsent(market, ignored -> new HashMap<>())
            .put(out.featureName(), out);
        totalAccepted.incrementAndGet();
        sequence++;
        notifyAll();
    }

    public synchronized List<FeatureOutput> snapshot(String marketTicker, String featureName) {
        Deque<FeatureOutput> deque = byMarket.get(marketTicker);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        List<FeatureOutput> result = new ArrayList<>();
        for (FeatureOutput output : deque) {
            if (featureName == null || featureName.equals(output.featureName())) {
                result.add(output);
            }
        }
        result.sort(Comparator.comparing(
            (FeatureOutput o) -> o.eventTsMs() == null ? Long.MIN_VALUE : o.eventTsMs()));
        return result;
    }

    public synchronized Set<String> symbols() {
        return new TreeSet<>(byMarket.keySet());
    }

    public synchronized int symbolCount() {
        return byMarket.size();
    }

    public synchronized Optional<FeatureOutput> latest(String marketTicker, String featureName) {
        Map<String, FeatureOutput> byFeature = latestByMarket.get(marketTicker);
        if (byFeature == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byFeature.get(featureName));
    }

    public long totalAccepted() {
        return totalAccepted.get();
    }

    public synchronized long sequence() {
        return sequence;
    }

    public synchronized long waitForSequenceAfter(long after, long timeoutMs) throws InterruptedException {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be non-negative");
        }
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (sequence <= after) {
            long remainingNs = deadlineNs - System.nanoTime();
            if (remainingNs <= 0L) {
                break;
            }
            long waitMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs));
            wait(waitMs);
        }
        return sequence;
    }

    public List<Bar> bars(String marketTicker, long fromMs, long toMs, BarResolution resolution) {
        if (resolution == null) {
            throw new IllegalArgumentException("resolution is required");
        }
        long bucketSize = resolution.bucketSizeMs();
        List<FeatureOutput> bbo = snapshot(marketTicker, BBO_FEATURE);
        if (bbo.isEmpty()) {
            return List.of();
        }
        Map<Long, BarAccumulator> buckets = new LinkedHashMap<>();
        for (FeatureOutput out : bbo) {
            Long ts = out.eventTsMs();
            if (ts == null) {
                continue;
            }
            if (ts < fromMs || ts > toMs) {
                continue;
            }
            Object midpoint = out.values().get("midpoint_micros");
            if (!(midpoint instanceof Number midpointNumber)) {
                continue;
            }
            double price = midpointNumber.longValue() / 1_000_000.0;
            long bucket = Math.floorDiv(ts, bucketSize) * bucketSize;
            BarAccumulator acc = buckets.computeIfAbsent(bucket, key -> new BarAccumulator(key, bucketSize));
            acc.observe(price);
        }
        List<Bar> bars = new ArrayList<>(buckets.size());
        for (BarAccumulator acc : buckets.values()) {
            if (acc.count > 0) {
                bars.add(acc.toBar());
            }
        }
        bars.sort(Comparator.comparingLong(Bar::openTimeMs));
        return bars;
    }

    private static final class BarAccumulator {
        private final long openTimeMs;
        private final long closeTimeMs;
        private double open;
        private double high;
        private double low;
        private double close;
        private long count;

        BarAccumulator(long openTimeMs, long bucketSize) {
            this.openTimeMs = openTimeMs;
            this.closeTimeMs = openTimeMs + bucketSize - 1L;
        }

        void observe(double price) {
            if (count == 0) {
                open = price;
                high = price;
                low = price;
            } else {
                if (price > high) {
                    high = price;
                }
                if (price < low) {
                    low = price;
                }
            }
            close = price;
            count++;
        }

        Bar toBar() {
            return new Bar(openTimeMs, closeTimeMs, open, high, low, close, count);
        }
    }
}
