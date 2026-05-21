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
    void stringApiCreatedCounterStorageIsSharedWithHandle() {
        BackendMetrics metrics = new BackendMetrics();
        Map<String, String> labels = BackendMetrics.labels("b", "2", "a", "1");

        metrics.increment("shared_total", labels);
        BackendMetrics.Counter counter = metrics.counter("shared_total", Map.of("a", "1", "b", "2"));
        counter.add(4L);

        assertEquals(5L, metrics.get("shared_total", labels));
        assertEquals(Map.of("shared_total{a=\"1\",b=\"2\"}", 5L), metrics.snapshot());
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
        assertTrue(prometheus.contains("latency_ns_recent_count{a=\"1\",b=\"2\"} 2\n"));
        assertTrue(prometheus.contains("latency_ns_recent_p50{a=\"1\",b=\"2\"} 5\n"));
        assertTrue(prometheus.contains("latency_ns_recent_p90{a=\"1\",b=\"2\"} 7\n"));
        assertTrue(prometheus.contains("latency_ns_recent_p95{a=\"1\",b=\"2\"} 7\n"));
        assertTrue(prometheus.contains("latency_ns_recent_p99{a=\"1\",b=\"2\"} 7\n"));
        assertTrue(prometheus.contains("latency_ns_recent_p999{a=\"1\",b=\"2\"} 7\n"));
    }

    @Test
    void unusedHandlesDoNotEmitZeroSeriesUntilUsed() {
        BackendMetrics metrics = new BackendMetrics();
        Map<String, String> labels = BackendMetrics.labels("stream", "canonical.trade");

        BackendMetrics.Counter counter = metrics.counter("unused_total", labels);
        BackendMetrics.DistributionHandle distribution = metrics.distribution("unused_latency_ns", labels);

        assertEquals(0L, metrics.get("unused_total", labels));
        assertEquals(Map.of(), metrics.snapshot());
        String unusedPrometheus = metrics.prometheusText();
        assertTrue(unusedPrometheus.isBlank());

        counter.increment();
        distribution.observe(9L);

        assertEquals(Map.of("unused_total{stream=\"canonical.trade\"}", 1L), metrics.snapshot());
        String prometheus = metrics.prometheusText();
        assertTrue(prometheus.contains("unused_total{stream=\"canonical.trade\"} 1\n"));
        assertTrue(prometheus.contains("unused_latency_ns_count{stream=\"canonical.trade\"} 1\n"));
        assertTrue(prometheus.contains("unused_latency_ns_sum{stream=\"canonical.trade\"} 9\n"));
        assertTrue(prometheus.contains("unused_latency_ns_max{stream=\"canonical.trade\"} 9\n"));
        assertTrue(prometheus.contains("unused_latency_ns_recent_count{stream=\"canonical.trade\"} 1\n"));
    }

    @Test
    void stringApiCreatedDistributionStorageIsSharedWithHandle() {
        BackendMetrics metrics = new BackendMetrics();
        Map<String, String> labels = BackendMetrics.labels("b", "2", "a", "1");

        metrics.observe("shared_latency_ns", labels, 2L);
        BackendMetrics.DistributionHandle distribution =
            metrics.distribution("shared_latency_ns", Map.of("a", "1", "b", "2"));
        distribution.observe(4L);

        String prometheus = metrics.prometheusText();
        assertTrue(prometheus.contains("shared_latency_ns_count{a=\"1\",b=\"2\"} 2\n"));
        assertTrue(prometheus.contains("shared_latency_ns_sum{a=\"1\",b=\"2\"} 6\n"));
        assertTrue(prometheus.contains("shared_latency_ns_max{a=\"1\",b=\"2\"} 4\n"));
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

    @Test
    void distributionRecentPercentilesUseBoundedObservedSamples() {
        BackendMetrics metrics = new BackendMetrics();
        Map<String, String> labels = BackendMetrics.labels("stream", "canonical.trade");
        BackendMetrics.DistributionHandle distribution = metrics.distribution("recent_latency_ns", labels);

        for (int value = 1; value <= 100; value++) {
            distribution.observe(value);
        }

        String prometheus = metrics.prometheusText();
        assertTrue(prometheus.contains("recent_latency_ns_count{stream=\"canonical.trade\"} 100\n"));
        assertTrue(prometheus.contains("recent_latency_ns_recent_count{stream=\"canonical.trade\"} 100\n"));
        assertTrue(prometheus.contains("recent_latency_ns_recent_p50{stream=\"canonical.trade\"} 50\n"));
        assertTrue(prometheus.contains("recent_latency_ns_recent_p90{stream=\"canonical.trade\"} 90\n"));
        assertTrue(prometheus.contains("recent_latency_ns_recent_p95{stream=\"canonical.trade\"} 95\n"));
        assertTrue(prometheus.contains("recent_latency_ns_recent_p99{stream=\"canonical.trade\"} 99\n"));
        assertTrue(prometheus.contains("recent_latency_ns_recent_p999{stream=\"canonical.trade\"} 100\n"));
    }
}
