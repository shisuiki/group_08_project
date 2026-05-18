package edu.illinois.group8.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

public class BackendMetrics {
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Distribution> distributions = new ConcurrentHashMap<>();

    public Counter counter(String name) {
        return counter(name, Map.of());
    }

    public Counter counter(String name, Map<String, String> labels) {
        return new Counter(metricKey(name, labels));
    }

    public DistributionHandle distribution(String name) {
        return distribution(name, Map.of());
    }

    public DistributionHandle distribution(String name, Map<String, String> labels) {
        return new DistributionHandle(metricKey(name, labels));
    }

    public void increment(String counterName) {
        increment(counterName, Map.of());
    }

    public void increment(String counterName, Map<String, String> labels) {
        add(counterName, labels, 1L);
    }

    public void add(String counterName, long amount) {
        add(counterName, Map.of(), amount);
    }

    public void add(String counterName, Map<String, String> labels, long amount) {
        addCounter(metricKey(counterName, labels), amount);
    }

    public void setGauge(String gaugeName, long value) {
        setGauge(gaugeName, Map.of(), value);
    }

    public void setGauge(String gaugeName, Map<String, String> labels, long value) {
        gauges.computeIfAbsent(metricKey(gaugeName, labels), ignored -> new AtomicLong()).set(value);
    }

    public void observe(String distributionName, long value) {
        observe(distributionName, Map.of(), value);
    }

    public void observe(String distributionName, Map<String, String> labels, long value) {
        observeDistribution(metricKey(distributionName, labels), value);
    }

    public long get(String counterName) {
        LongAdder adder = counters.get(metricKey(counterName, Map.of()));
        return adder == null ? 0L : adder.sum();
    }

    public long get(String counterName, Map<String, String> labels) {
        LongAdder adder = counters.get(metricKey(counterName, labels));
        return adder == null ? 0L : adder.sum();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        counters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue().sum()));
        return Collections.unmodifiableMap(snapshot);
    }

    public String prometheusText() {
        StringBuilder body = new StringBuilder();
        counters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> body.append(entry.getKey()).append(' ').append(entry.getValue().sum()).append('\n'));
        gauges.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> body.append(entry.getKey()).append(' ').append(entry.getValue().get()).append('\n'));
        distributions.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> entry.getValue().appendPrometheus(body, entry.getKey()));
        return body.toString();
    }

    public static Map<String, String> labels(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Metric labels must be key/value pairs.");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = keyValues[i];
            String value = keyValues[i + 1];
            if (value != null && !value.isBlank()) {
                labels.put(key, value);
            }
        }
        return labels;
    }

    private static String metricKey(String name, Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return name;
        }
        return name + "{" + labels.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=\"" + escapeLabel(entry.getValue()) + "\"")
            .collect(Collectors.joining(",")) + "}";
    }

    private static String escapeLabel(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\"", "\\\"");
    }

    private void addCounter(String key, long amount) {
        counters.computeIfAbsent(key, ignored -> new LongAdder()).add(amount);
    }

    private void observeDistribution(String key, long value) {
        distributions.computeIfAbsent(key, ignored -> new Distribution()).observe(value);
    }

    public final class Counter {
        private final String key;

        private Counter(String key) {
            this.key = key;
        }

        public void increment() {
            add(1L);
        }

        public void add(long amount) {
            addCounter(key, amount);
        }
    }

    public final class DistributionHandle {
        private final String key;

        private DistributionHandle(String key) {
            this.key = key;
        }

        public void observe(long value) {
            observeDistribution(key, value);
        }
    }

    private static final class Distribution {
        private final LongAdder count = new LongAdder();
        private final LongAdder sum = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        private void observe(long value) {
            count.increment();
            sum.add(value);
            max.accumulateAndGet(value, Math::max);
        }

        private void appendPrometheus(StringBuilder body, String baseKey) {
            String countKey = insertSuffix(baseKey, "_count");
            String sumKey = insertSuffix(baseKey, "_sum");
            String maxKey = insertSuffix(baseKey, "_max");
            body.append(countKey).append(' ').append(count.sum()).append('\n');
            body.append(sumKey).append(' ').append(sum.sum()).append('\n');
            body.append(maxKey).append(' ').append(max.get()).append('\n');
        }

        private static String insertSuffix(String key, String suffix) {
            int labelStart = key.indexOf('{');
            if (labelStart < 0) {
                return key + suffix;
            }
            return key.substring(0, labelStart) + suffix + key.substring(labelStart);
        }
    }
}
