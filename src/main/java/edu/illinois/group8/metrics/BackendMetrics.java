package edu.illinois.group8.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class BackendMetrics {
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void increment(String counterName) {
        counters.computeIfAbsent(counterName, ignored -> new LongAdder()).increment();
    }

    public void add(String counterName, long amount) {
        counters.computeIfAbsent(counterName, ignored -> new LongAdder()).add(amount);
    }

    public long get(String counterName) {
        LongAdder adder = counters.get(counterName);
        return adder == null ? 0L : adder.sum();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        counters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue().sum()));
        return Collections.unmodifiableMap(snapshot);
    }
}
