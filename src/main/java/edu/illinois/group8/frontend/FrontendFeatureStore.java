package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.storage.db.FeatureOutputRow;

import java.time.Instant;
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
    private final Map<String, Map<String, LatestFeature>> latestByMarket = new HashMap<>();
    private final AtomicLong totalAccepted = new AtomicLong();
    private LatestFeature latestGlobal;
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
        accept(out, null, null);
    }

    public synchronized void accept(FeatureOutputRow row) {
        if (row == null) {
            return;
        }
        accept(row.output(), row.createdAt(), row.featureEventId());
    }

    private void accept(FeatureOutput out, Instant createdAt, String featureEventId) {
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
                    recomputeLatestGlobal();
                }
            }
            deque = new ArrayDeque<>(Math.min(maxFeaturesPerMarket, 1024));
            byMarket.put(market, deque);
        }
        deque.addLast(out);
        while (deque.size() > maxFeaturesPerMarket) {
            deque.removeFirst();
        }
        long acceptedSequence = sequence + 1L;
        LatestKey key = LatestKey.of(out, createdAt, featureEventId, acceptedSequence);
        Map<String, LatestFeature> latestByFeature = latestByMarket
            .computeIfAbsent(market, ignored -> new HashMap<>());
        LatestFeature current = latestByFeature.get(out.featureName());
        if (current == null || key.compareTo(current.key()) >= 0) {
            latestByFeature.put(out.featureName(), new LatestFeature(out, key));
        }
        if (latestGlobal == null || key.compareTo(latestGlobal.key()) >= 0) {
            latestGlobal = new LatestFeature(out, key);
        }
        totalAccepted.incrementAndGet();
        sequence = acceptedSequence;
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
        Map<String, LatestFeature> byFeature = latestByMarket.get(marketTicker);
        if (byFeature == null) {
            return Optional.empty();
        }
        LatestFeature latest = byFeature.get(featureName);
        return latest == null ? Optional.empty() : Optional.of(latest.output());
    }

    public long totalAccepted() {
        return totalAccepted.get();
    }

    public synchronized long sequence() {
        return sequence;
    }

    private void recomputeLatestGlobal() {
        LatestFeature best = null;
        for (Map<String, LatestFeature> byFeature : latestByMarket.values()) {
            for (LatestFeature candidate : byFeature.values()) {
                if (best == null || candidate.key().compareTo(best.key()) >= 0) {
                    best = candidate;
                }
            }
        }
        latestGlobal = best;
    }

    public synchronized DataFreshness latestFreshness(long nowMs) {
        if (latestGlobal == null) {
            return new DataFreshness(null, null, null, null, null, sequence);
        }
        FeatureOutput output = latestGlobal.output();
        Long eventTsMs = output.eventTsMs();
        Long eventAgeMs = eventTsMs == null ? null : Math.max(0L, nowMs - eventTsMs);
        return new DataFreshness(
            eventTsMs,
            eventAgeMs,
            output.marketTicker(),
            output.featureName(),
            output.sourceEventId(),
            sequence
        );
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

    private record LatestFeature(FeatureOutput output, LatestKey key) {
    }

    public record DataFreshness(
        Long latestEventTsMs,
        Long latestEventAgeMs,
        String symbol,
        String featureName,
        String sourceEventId,
        long storeSequence
    ) {
    }

    private record LatestKey(long eventTsMs, Instant createdAt, String featureEventId, long acceptedSequence)
        implements Comparable<LatestKey> {
        private static LatestKey of(
            FeatureOutput output,
            Instant createdAt,
            String featureEventId,
            long acceptedSequence
        ) {
            long eventTsMs = output.eventTsMs() == null ? Long.MIN_VALUE : output.eventTsMs();
            Instant cursorCreatedAt = createdAt == null ? Instant.EPOCH : createdAt;
            String cursorEventId = featureEventId;
            if (cursorEventId == null || cursorEventId.isBlank()) {
                cursorEventId = output.sourceEventId();
            }
            if (cursorEventId == null) {
                cursorEventId = "";
            }
            return new LatestKey(eventTsMs, cursorCreatedAt, cursorEventId, acceptedSequence);
        }

        @Override
        public int compareTo(LatestKey other) {
            int eventTsCompare = Long.compare(eventTsMs, other.eventTsMs);
            if (eventTsCompare != 0) {
                return eventTsCompare;
            }
            int createdAtCompare = createdAt.compareTo(other.createdAt);
            if (createdAtCompare != 0) {
                return createdAtCompare;
            }
            int eventIdCompare = featureEventId.compareTo(other.featureEventId);
            if (eventIdCompare != 0) {
                return eventIdCompare;
            }
            return Long.compare(acceptedSequence, other.acceptedSequence);
        }
    }
}
