package edu.illinois.group8.frontend;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusHotPathLatencyReaderTest {
    @Test
    void disabledWhenMetricsUrlsAreMissing() {
        Supplier<HotPathLatencyStatus> supplier = PrometheusHotPathLatencyReader.fromEnvironment(Map.of());

        HotPathLatencyStatus status = supplier.get();

        assertEquals("disabled", status.status());
    }

    @Test
    void parsesHotPathDistributionsFromPrometheusText() {
        String backend = """
            backend_hot_path_ws_to_tickerplant_publish_ns_count{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 2
            backend_hot_path_ws_to_tickerplant_publish_ns_sum{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 12000
            backend_hot_path_ws_to_tickerplant_publish_ns_max{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 8000
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_count{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 2
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p50{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 4000
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p90{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 8000
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p99{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 8000
            """;
        String featureplant = """
            featureplant_hot_path_consumer_to_module_complete_ns_count{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 1
            featureplant_hot_path_consumer_to_module_complete_ns_sum{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            featureplant_hot_path_consumer_to_module_complete_ns_max{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            featureplant_hot_path_consumer_to_module_complete_ns_recent_count{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 1
            featureplant_hot_path_consumer_to_module_complete_ns_recent_p50{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            featureplant_hot_path_consumer_to_module_complete_ns_recent_p90{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            featureplant_hot_path_consumer_to_module_complete_ns_recent_p99{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            feature_module_latency_ns_count{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 2
            feature_module_latency_ns_sum{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 900
            feature_module_latency_ns_max{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 600
            feature_module_latency_ns_recent_count{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 2
            feature_module_latency_ns_recent_p50{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 300
            feature_module_latency_ns_recent_p90{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 600
            feature_module_latency_ns_recent_p99{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 600
            """;

        HotPathLatencyStatus status = PrometheusHotPathLatencyReader.fromPrometheusTexts(backend, featureplant);

        assertEquals("ok", status.status());
        assertEquals(3, status.stages().size());
        assertTrue(status.note().contains("excludes"));
        HotPathLatencyStatus.Stage backendStage = status.stages().get(0);
        assertEquals("ws_to_tickerplant_publish", backendStage.id());
        assertEquals("backend_hot_path_ws_to_tickerplant_publish_ns", backendStage.metric());
        assertEquals(8_000L, backendStage.series().get(0).p99Ns());
        HotPathLatencyStatus.Stage featureStage = status.stages().get(1);
        assertEquals("featureplant_consumer_to_bbo_complete", featureStage.id());
        assertEquals(3_000L, featureStage.series().get(0).p99Ns());
        HotPathLatencyStatus.Stage moduleStage = status.stages().get(2);
        assertEquals("featureplant_bbo_module_processing", moduleStage.id());
        assertEquals(600L, moduleStage.series().get(0).p99Ns());
    }

    @Test
    void rejectsInvalidTimeout() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusHotPathLatencyReader.fromEnvironment(Map.of(
                PrometheusHotPathLatencyReader.TIMEOUT_MS_ENV,
                "0",
                PrometheusHotPathLatencyReader.BACKEND_METRICS_URL_ENV,
                "http://127.0.0.1:1/metrics"
            ))
        );

        assertTrue(thrown.getMessage().contains(PrometheusHotPathLatencyReader.TIMEOUT_MS_ENV));
    }
}
