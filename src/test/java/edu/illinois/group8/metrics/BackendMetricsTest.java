package edu.illinois.group8.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendMetricsTest {
    @Test
    void counterHandleUsesSameSortedKeyAsExistingCounterApi() {
        BackendMetrics metrics = new BackendMetrics();
        BackendMetrics.Counter counter = metrics.counter("events_total", Map.of("b", "2", "a", "1"));

        counter.increment();
        metrics.add("events_total", BackendMetrics.labels("a", "1", "b", "2"), 2L);

        assertEquals(3L, metrics.get("events_total", BackendMetrics.labels("b", "2", "a", "1")));
        assertEquals(Map.of("events_total{a=\"1\",b=\"2\"}", 3L), metrics.snapshot());
    }

    @Test
    void counterHandleDropsBlankAndNullLabelsLikeExistingApi() {
        BackendMetrics metrics = new BackendMetrics();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("a", "1");
        labels.put("blank", "");
        labels.put("null_value", null);

        metrics.counter("blank_total", labels).increment();
        metrics.increment("blank_total", BackendMetrics.labels("a", "1"));

        assertEquals(2L, metrics.get("blank_total", BackendMetrics.labels("a", "1")));
        assertEquals(Map.of("blank_total{a=\"1\"}", 2L), metrics.snapshot());
    }

    @Test
    void distributionHandleUsesSamePrometheusKeysAsExistingObserveApi() {
        BackendMetrics metrics = new BackendMetrics();
        BackendMetrics.DistributionHandle distribution =
            metrics.distribution("latency_ns", Map.of("b", "2", "a", "1"));

        distribution.observe(5L);
        metrics.observe("latency_ns", BackendMetrics.labels("a", "1", "b", "2"), 7L);

        String prometheus = metrics.prometheusText();
        assertTrue(prometheus.contains("latency_ns_count{a=\"1\",b=\"2\"} 2\n"));
        assertTrue(prometheus.contains("latency_ns_sum{a=\"1\",b=\"2\"} 12\n"));
        assertTrue(prometheus.contains("latency_ns_max{a=\"1\",b=\"2\"} 7\n"));
    }

    @Test
    void handlesPreserveExistingLabelEscaping() {
        BackendMetrics metrics = new BackendMetrics();
        Map<String, String> labels = Map.of("value", "a\"b\nc\\d");

        metrics.counter("escaped_total", labels).increment();
        metrics.increment("escaped_total", labels);
        metrics.distribution("escaped_latency_ns", labels).observe(3L);

        String prometheus = metrics.prometheusText();
        assertTrue(prometheus.contains("escaped_total{value=\"a\\\"b\\nc\\\\d\"} 2\n"));
        assertTrue(prometheus.contains("escaped_latency_ns_count{value=\"a\\\"b\\nc\\\\d\"} 1\n"));
    }
}
