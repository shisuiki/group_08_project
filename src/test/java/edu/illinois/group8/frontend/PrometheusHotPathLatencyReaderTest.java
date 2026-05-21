package edu.illinois.group8.frontend;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
            wsclient_hot_path_receive_to_cluster_offer_ns_count{message_type="orderbook_snapshot",result="accepted",service="wsclient",source="kalshi"} 99
            wsclient_hot_path_receive_to_cluster_offer_ns_recent_count{message_type="orderbook_snapshot",result="accepted",service="wsclient",source="kalshi"} 99
            wsclient_hot_path_receive_to_cluster_offer_ns_recent_p99{message_type="orderbook_snapshot",result="accepted",service="wsclient",source="kalshi"} 900000
            wsclient_hot_path_receive_to_cluster_offer_ns_count{message_type="orderbook_delta",result="accepted",service="wsclient",source="kalshi"} 2
            wsclient_hot_path_receive_to_cluster_offer_ns_sum{message_type="orderbook_delta",result="accepted",service="wsclient",source="kalshi"} 2000
            wsclient_hot_path_receive_to_cluster_offer_ns_max{message_type="orderbook_delta",result="accepted",service="wsclient",source="kalshi"} 1200
            wsclient_hot_path_receive_to_cluster_offer_ns_recent_count{message_type="orderbook_delta",result="accepted",service="wsclient",source="kalshi"} 2
            wsclient_hot_path_receive_to_cluster_offer_ns_recent_p50{message_type="orderbook_delta",result="accepted",service="wsclient",source="kalshi"} 900
            wsclient_hot_path_receive_to_cluster_offer_ns_recent_p95{message_type="orderbook_delta",result="accepted",service="wsclient",source="kalshi"} 1200
            wsclient_hot_path_receive_to_cluster_offer_ns_recent_p99{message_type="orderbook_delta",result="accepted",service="wsclient",source="kalshi"} 1200
            wsclient_hot_path_receive_to_cluster_offer_ns_recent_p999{message_type="orderbook_delta",result="accepted",service="wsclient",source="kalshi"} 1200
            backend_hot_path_cluster_receive_to_tickerplant_publish_ns_count{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 2
            backend_hot_path_cluster_receive_to_tickerplant_publish_ns_sum{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 6000
            backend_hot_path_cluster_receive_to_tickerplant_publish_ns_max{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 4000
            backend_hot_path_cluster_receive_to_tickerplant_publish_ns_recent_count{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 2
            backend_hot_path_cluster_receive_to_tickerplant_publish_ns_recent_p50{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 2000
            backend_hot_path_cluster_receive_to_tickerplant_publish_ns_recent_p95{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 4000
            backend_hot_path_cluster_receive_to_tickerplant_publish_ns_recent_p99{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 4000
            backend_hot_path_cluster_receive_to_tickerplant_publish_ns_recent_p999{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 4000
            backend_hot_path_canonical_parse_ns_count{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 1
            backend_hot_path_canonical_parse_ns_sum{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 700
            backend_hot_path_canonical_parse_ns_recent_count{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 1
            backend_hot_path_canonical_parse_ns_recent_p99{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 700
            backend_hot_path_tickerplant_publish_offer_ns_count{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 1
            backend_hot_path_tickerplant_publish_offer_ns_sum{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 500
            backend_hot_path_tickerplant_publish_offer_ns_recent_count{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 1
            backend_hot_path_tickerplant_publish_offer_ns_recent_p99{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 500
            backend_hot_path_ws_to_tickerplant_publish_ns_count{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 2
            backend_hot_path_ws_to_tickerplant_publish_ns_sum{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 12000
            backend_hot_path_ws_to_tickerplant_publish_ns_max{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 8000
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_count{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 2
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p50{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 4000
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p90{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 8000
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p95{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 8000
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p99{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 8000
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p999{event_type="orderbook_delta",schema_version="1",service="backend",source="kalshi",stream="canonical.orderbook.delta"} 8000
            """;
        String featureplant = """
            featureplant_hot_path_consumer_to_module_complete_ns_count{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 1
            featureplant_hot_path_consumer_to_module_complete_ns_sum{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            featureplant_hot_path_consumer_to_module_complete_ns_max{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            featureplant_hot_path_consumer_to_module_complete_ns_recent_count{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 1
            featureplant_hot_path_consumer_to_module_complete_ns_recent_p50{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            featureplant_hot_path_consumer_to_module_complete_ns_recent_p90{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            featureplant_hot_path_consumer_to_module_complete_ns_recent_p95{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            featureplant_hot_path_consumer_to_module_complete_ns_recent_p99{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            featureplant_hot_path_consumer_to_module_complete_ns_recent_p999{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 3000
            feature_module_latency_ns_count{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 2
            feature_module_latency_ns_sum{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 900
            feature_module_latency_ns_max{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 600
            feature_module_latency_ns_recent_count{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 2
            feature_module_latency_ns_recent_p50{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 300
            feature_module_latency_ns_recent_p90{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 600
            feature_module_latency_ns_recent_p95{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 600
            feature_module_latency_ns_recent_p99{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 600
            feature_module_latency_ns_recent_p999{module="feature.bbo",service="featureplant",stream="derived.top_of_book"} 600
            """;

        HotPathLatencyStatus status = PrometheusHotPathLatencyReader.fromPrometheusTexts(backend, featureplant);

        assertEquals("ok", status.status());
        assertEquals(7, status.stages().size());
        assertTrue(status.note().contains("excludes"));
        HotPathLatencyStatus.Stage wsclientStage = stage(status, "wsclient_receive_to_cluster_offer");
        assertEquals("wsclient_hot_path_receive_to_cluster_offer_ns", wsclientStage.metric());
        assertEquals("orderbook_delta", wsclientStage.series().get(0).labels().get("message_type"));
        assertEquals(1_200L, wsclientStage.series().get(0).p99Ns());
        HotPathLatencyStatus.Stage clusterStage = stage(status, "cluster_receive_to_tickerplant_publish");
        assertEquals("backend_hot_path_cluster_receive_to_tickerplant_publish_ns", clusterStage.metric());
        assertEquals(4_000L, clusterStage.series().get(0).p99Ns());
        assertEquals(700L, stage(status, "canonical_parse").series().get(0).p99Ns());
        assertEquals(500L, stage(status, "tickerplant_publish_offer").series().get(0).p99Ns());
        HotPathLatencyStatus.Stage legacyBackendStage = stage(status, "ws_to_tickerplant_publish");
        assertEquals("backend_hot_path_ws_to_tickerplant_publish_ns", legacyBackendStage.metric());
        assertEquals(8_000L, legacyBackendStage.series().get(0).p95Ns());
        assertEquals(8_000L, legacyBackendStage.series().get(0).p99Ns());
        assertEquals(8_000L, legacyBackendStage.series().get(0).p999Ns());
        HotPathLatencyStatus.Stage featureStage = stage(status, "featureplant_consumer_to_bbo_complete");
        assertEquals(3_000L, featureStage.series().get(0).p99Ns());
        HotPathLatencyStatus.Stage moduleStage = stage(status, "featureplant_bbo_module_processing");
        assertEquals(600L, moduleStage.series().get(0).p99Ns());
    }

    @Test
    void mergesBackendSeriesFromMultiplePrometheusTexts() {
        String first = """
            backend_hot_path_ws_to_tickerplant_publish_ns_count{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 2
            backend_hot_path_ws_to_tickerplant_publish_ns_sum{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 100
            backend_hot_path_ws_to_tickerplant_publish_ns_max{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 70
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_count{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 2
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p50{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 40
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p90{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 70
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p95{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 70
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p99{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 70
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p999{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 70
            """;
        String second = """
            backend_hot_path_ws_to_tickerplant_publish_ns_count{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 3
            backend_hot_path_ws_to_tickerplant_publish_ns_sum{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 200
            backend_hot_path_ws_to_tickerplant_publish_ns_max{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 90
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_count{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 3
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p50{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 50
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p90{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 80
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p95{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 85
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p99{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 90
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p999{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 90
            """;

        HotPathLatencyStatus status = PrometheusHotPathLatencyReader.fromPrometheusTexts(first + "\n" + second, "");
        HotPathLatencyStatus.Series series = stage(status, "ws_to_tickerplant_publish").series().get(0);

        assertEquals(5L, series.count());
        assertEquals(5L, series.recentCount());
        assertEquals(60L, series.avgNs());
        assertEquals(90L, series.maxNs());
        assertEquals(85L, series.p95Ns());
        assertEquals(90L, series.p99Ns());
        assertEquals(90L, series.p999Ns());
    }

    @Test
    void fetchesCommaSeparatedBackendMetricsUrls() throws Exception {
        HttpServer first = metricsServer("""
            backend_hot_path_ws_to_tickerplant_publish_ns_count{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 1
            backend_hot_path_ws_to_tickerplant_publish_ns_sum{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 10
            backend_hot_path_ws_to_tickerplant_publish_ns_max{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 10
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_count{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 1
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p99{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 10
            """);
        HttpServer second = metricsServer("""
            backend_hot_path_ws_to_tickerplant_publish_ns_count{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 1
            backend_hot_path_ws_to_tickerplant_publish_ns_sum{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 30
            backend_hot_path_ws_to_tickerplant_publish_ns_max{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 30
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_count{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 1
            backend_hot_path_ws_to_tickerplant_publish_ns_recent_p99{event_type="trade",schema_version="1",service="backend",source="kalshi",stream="canonical.trade"} 30
            """);
        try {
            HotPathLatencyStatus status = PrometheusHotPathLatencyReader.fromEnvironment(Map.of(
                PrometheusHotPathLatencyReader.BACKEND_METRICS_URLS_ENV,
                "http://127.0.0.1:" + first.getAddress().getPort() + "/metrics,"
                    + "http://127.0.0.1:" + second.getAddress().getPort() + "/metrics",
                PrometheusHotPathLatencyReader.BACKEND_METRICS_URL_ENV,
                "http://127.0.0.1:1/metrics",
                PrometheusHotPathLatencyReader.TIMEOUT_MS_ENV,
                "250"
            )).get();

            HotPathLatencyStatus.Series series = stage(status, "ws_to_tickerplant_publish").series().get(0);
            assertEquals(2L, series.count());
            assertEquals(20L, series.avgNs());
            assertEquals(30L, series.p99Ns());
        } finally {
            first.stop(0);
            second.stop(0);
        }
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

    private static HotPathLatencyStatus.Stage stage(HotPathLatencyStatus status, String id) {
        return status.stages().stream()
            .filter(item -> id.equals(item.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing stage " + id));
    }

    private static HttpServer metricsServer(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metrics", exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
