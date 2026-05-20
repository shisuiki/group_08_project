package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.storage.db.FeatureOutputRow;
import edu.illinois.group8.storage.db.MarketMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdfEndpointsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FrontendAdapterServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        seed(store);
        FrontendAdapterConfig config = new FrontendAdapterConfig(
            "127.0.0.1",
            0,
            FrontendAdapterConfig.SourceMode.RECORDING,
            "aeron:udp?endpoint=224.0.1.1:40456",
            Path.of("recordings"),
            edu.illinois.group8.canonical.StreamRegistry.normalizedStreams().subList(0, 1),
            List.of("bbo"),
            10_000,
            5_000,
            64,
            1,
            0L,
            "",
            "",
            "",
            false,
            ""
        );
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.loaded("test", List.of(
                metadata("MKT-1", "EVENT-1", "SERIES-1", "open"),
                metadata("MKT-META", "EVENT-META", "SERIES-META", "closed")
            )),
            () -> new FrontendAdapterServer.FeaturePlantStats(42L, 17L, 0L)
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void datafeedConfigShape() throws Exception {
        JsonNode body = getJson("/datafeed/config");
        assertTrue(body.path("supports_search").asBoolean());
        assertTrue(body.path("supports_time").asBoolean());
        JsonNode resolutions = body.path("supported_resolutions");
        assertEquals(7, resolutions.size());
        assertEquals("1S", resolutions.get(0).asText());
        assertEquals("60", resolutions.get(6).asText());
    }

    @Test
    void datafeedSymbolsShape() throws Exception {
        JsonNode body = getJson("/datafeed/symbols?symbol=MKT-1");
        assertEquals("MKT-1", body.path("name").asText());
        assertEquals("MKT-1", body.path("ticker").asText());
        assertEquals("EVENT-1 / SERIES-1 / open", body.path("description").asText());
        assertEquals("EVENT-1", body.path("event_ticker").asText());
        assertEquals("SERIES-1", body.path("series_ticker").asText());
        assertEquals("open", body.path("status").asText());
        assertEquals("binary", body.path("type").asText());
        assertEquals("24x7", body.path("session").asText());
        assertEquals("Etc/UTC", body.path("timezone").asText());
        assertEquals(1, body.path("minmov").asInt());
        assertEquals(1_000_000, body.path("pricescale").asInt());
        assertTrue(body.path("has_intraday").asBoolean());
        assertTrue(body.path("has_seconds").asBoolean());
    }

    @Test
    void datafeedSearchMatchesSubstring() throws Exception {
        JsonNode body = getJson("/datafeed/search?query=MKT&limit=10");
        assertTrue(body.isArray());
        assertTrue(body.size() >= 1);
        assertEquals("Kalshi", body.get(0).path("exchange").asText());
        assertEquals("binary", body.get(0).path("type").asText());
    }

    @Test
    void datafeedSearchFindsMetadataOnlyMarkets() throws Exception {
        JsonNode body = getJson("/datafeed/search?query=EVENT-META&limit=10");

        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("MKT-META", body.get(0).path("symbol").asText());
        assertEquals("EVENT-META / SERIES-META / closed", body.get(0).path("description").asText());
        assertEquals("closed", body.get(0).path("status").asText());
    }

    @Test
    void marketsEndpointReturnsCompactFilteredMetadata() throws Exception {
        JsonNode body = getJson("/markets?status=closed&limit=10");

        assertEquals(1, body.path("count").asInt());
        JsonNode market = body.path("markets").get(0);
        assertEquals("MKT-META", market.path("market_ticker").asText());
        assertEquals("EVENT-META", market.path("event_ticker").asText());
        assertEquals("SERIES-META", market.path("series_ticker").asText());
        assertEquals("closed", market.path("status").asText());
        assertEquals("2026-05-20T01:00:00Z", market.path("open_time").asText());
        assertTrue(market.path("market_payload").isMissingNode());
        assertTrue(market.path("rules_payload").isMissingNode());
    }

    @Test
    void marketsEndpointRejectsBadLimit() throws Exception {
        HttpResponse<String> badLimit = get("/markets?limit=0");

        assertEquals(400, badLimit.statusCode());
        assertTrue(badLimit.body().contains("limit must be positive"));
    }

    @Test
    void datafeedHistoryReturnsBars() throws Exception {
        long fromSec = 0L;
        long toSec = 10L;
        JsonNode body = getJson("/datafeed/history?symbol=MKT-1&resolution=1S&from=" + fromSec + "&to=" + toSec);
        assertEquals("ok", body.path("s").asText());
        JsonNode t = body.path("t");
        assertEquals(t.size(), body.path("o").size());
        assertEquals(t.size(), body.path("h").size());
        assertEquals(t.size(), body.path("l").size());
        assertEquals(t.size(), body.path("c").size());
        assertEquals(t.size(), body.path("v").size());
        assertTrue(t.size() >= 1);
        assertTrue(body.path("o").get(0).isNumber());
    }

    @Test
    void datafeedHistoryNoDataWhenWindowEmpty() throws Exception {
        JsonNode body = getJson("/datafeed/history?symbol=MKT-1&resolution=1S&from=900000&to=999999");
        assertEquals("no_data", body.path("s").asText());
    }

    @Test
    void datafeedTimeIsUnixSeconds() throws Exception {
        HttpResponse<String> response = get("/datafeed/time");
        assertEquals(200, response.statusCode());
        long now = System.currentTimeMillis() / 1000L;
        long parsed = Long.parseLong(response.body().trim());
        assertTrue(Math.abs(now - parsed) < 5L, "time drift: " + parsed + " vs " + now);
    }

    @Test
    void servesTradingViewStaticIndex() throws Exception {
        HttpResponse<String> root = get("/");
        HttpResponse<String> index = get("/index.html");

        assertEquals(200, root.statusCode());
        assertEquals(200, index.statusCode());
        assertTrue(root.headers().firstValue("content-type").orElse("").contains("text/html"));
        assertTrue(root.body().contains("Kalshi Product Dashboard"));
        assertTrue(root.body().contains("market-list"));
        assertTrue(root.body().contains("feature-list"));
        assertTrue(root.body().contains("Runtime Health"));
        assertTrue(root.body().contains("placeholder=\"same origin\""));
        assertTrue(index.body().contains("<script src=\"app.js\"></script>"));
    }

    @Test
    void servesTradingViewJavascriptAndStyles() throws Exception {
        HttpResponse<String> js = get("/app.js");
        HttpResponse<String> css = get("/styles.css");

        assertEquals(200, js.statusCode());
        assertEquals(200, css.statusCode());
        assertTrue(js.headers().firstValue("content-type").orElse("").contains("text/javascript"));
        assertTrue(css.headers().firstValue("content-type").orElse("").contains("text/css"));
        assertTrue(js.body().contains("/quotes/updates?symbols="));
        assertTrue(js.body().contains("/markets?limit=100"));
        assertTrue(js.body().contains("/features?symbol="));
        assertTrue(js.body().contains("/health"));
        assertTrue(js.body().contains("nextSequence < quoteSequence"));
        assertTrue(js.body().contains("window.location.origin"));
        assertTrue(css.body().contains("chart-container"));
    }

    @Test
    void staticFallbackRejectsUnknownAndTraversalPaths() throws Exception {
        HttpResponse<String> missing = get("/missing.js");
        HttpResponse<String> traversal = get("/%2e%2e/pom.xml");

        assertEquals(404, missing.statusCode());
        assertEquals(404, traversal.statusCode());
        assertFalse(traversal.body().contains("<artifactId>"));
    }

    @Test
    void apiRoutesStillWinOverStaticFallback() throws Exception {
        assertTrue(getJson("/datafeed/config").path("supports_search").asBoolean());
        assertEquals("ok", getJson("/health").path("status").asText());
        assertEquals(1, getJson("/quotes?symbols=MKT-1").path("quotes").size());
    }

    @Test
    void missingStaticRootReturnsNotFoundWithoutCrashingApi() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_STATIC_ROOT", "target/missing-static-assets"
        ));
        server = new FrontendAdapterServer(
            config,
            new FrontendFeatureStore(100, 100),
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        assertEquals(404, get("/").statusCode());
        assertEquals("ok", getJson("/health").path("status").asText());
    }

    @Test
    void symbolsAndQuotesShape() throws Exception {
        JsonNode symbols = getJson("/symbols");
        assertTrue(symbols.path("symbols").isArray());
        assertTrue(symbols.path("symbols").size() >= 1);
        JsonNode quotes = getJson("/quotes?symbols=MKT-1,MKT-MISSING");
        assertTrue(quotes.path("sequence").asLong() > 0L);
        assertEquals(2, quotes.path("quotes").size());
        assertEquals("MKT-1", quotes.path("quotes").get(0).path("symbol").asText());
        assertNotNull(quotes.path("quotes").get(0).path("midpoint_micros"));
        assertEquals("evt-5000", quotes.path("quotes").get(0).path("source_event_id").asText());
    }

    @Test
    void quoteUpdatesBootstrapAndStaleAfterReturnImmediately() throws Exception {
        JsonNode bootstrap = getJson("/quotes/updates?symbols=MKT-1");

        assertFalse(bootstrap.path("changed").asBoolean());
        assertTrue(bootstrap.path("sequence").asLong() > 0L);
        assertEquals(1, bootstrap.path("quotes").size());
        assertEquals("MKT-1", bootstrap.path("quotes").get(0).path("symbol").asText());

        JsonNode stale = getJson("/quotes/updates?symbols=MKT-1&after=0&timeout_ms=1000");

        assertTrue(stale.path("changed").asBoolean());
        assertEquals(bootstrap.path("sequence").asLong(), stale.path("sequence").asLong());
        assertEquals(1, stale.path("quotes").size());
    }

    @Test
    void quoteUpdatesTimesOutBoundedlyWhenNoRowsArrive() throws Exception {
        long sequence = getJson("/quotes?symbols=MKT-1").path("sequence").asLong();
        long startedNs = System.nanoTime();

        JsonNode body = getJson("/quotes/updates?symbols=MKT-1&after=" + sequence + "&timeout_ms=25");

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        assertFalse(body.path("changed").asBoolean());
        assertEquals(sequence, body.path("sequence").asLong());
        assertTrue(elapsedMs < 2_000L, "long-poll timeout was not bounded: " + elapsedMs);
        JsonNode health = getJson("/health");
        assertTrue(health.path("quote_updates").path("timeouts").asLong() >= 1L);
    }

    @Test
    void quoteUpdateLongPollsDoNotStarveHealthEndpoint() throws Exception {
        long sequence = getJson("/quotes?symbols=MKT-1").path("sequence").asLong();
        List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pending.add(client.sendAsync(
                HttpRequest.newBuilder(URI.create(baseUrl
                    + "/quotes/updates?symbols=MKT-1&after=" + sequence + "&timeout_ms=750"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            ));
        }

        JsonNode health = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            HttpResponse<String> response = getWithTimeout("/health", 500L);
            assertEquals(200, response.statusCode());
            health = MAPPER.readTree(response.body());
            if (health.path("quote_updates").path("active_waits").asLong() > 0L) {
                break;
            }
            Thread.sleep(25L);
        }

        assertNotNull(health);
        assertEquals("ok", health.path("status").asText());
        assertTrue(health.path("quote_updates").path("active_waits").asLong() > 0L);
        assertEquals(4, health.path("quote_updates").path("max_waits").asInt());
        for (CompletableFuture<HttpResponse<String>> future : pending) {
            assertEquals(200, future.get(2, TimeUnit.SECONDS).statusCode());
        }
    }

    @Test
    void featuresEndpointReturnsSeededOutputsWithLimit() throws Exception {
        JsonNode body = getJson("/features?symbol=MKT-1&feature=feature.bbo&limit=2");

        assertEquals("MKT-1", body.path("symbol").asText());
        assertEquals("feature.bbo", body.path("feature").asText());
        assertEquals(2, body.path("count").asInt());
        JsonNode outputs = body.path("outputs");
        assertEquals(2, outputs.size());
        assertEquals("feature.bbo", outputs.get(0).path("feature_name").asText());
        assertEquals("MKT-1", outputs.get(0).path("market_ticker").asText());
        assertEquals(4_500L, outputs.get(0).path("event_ts_ms").asLong());
        assertEquals("evt-4500", outputs.get(0).path("source_event_id").asText());
        assertEquals(504_500L, outputs.get(0).path("values").path("midpoint_micros").asLong());
        assertEquals(5_000L, outputs.get(1).path("event_ts_ms").asLong());
    }

    @Test
    void featuresEndpointRejectsMissingRequiredParamsAndBadLimit() throws Exception {
        HttpResponse<String> missing = get("/features?symbol=MKT-1");
        assertEquals(400, missing.statusCode());
        assertTrue(missing.body().contains("symbol and feature are required"));

        HttpResponse<String> badLimit = get("/features?symbol=MKT-1&feature=feature.bbo&limit=0");
        assertEquals(400, badLimit.statusCode());
        assertTrue(badLimit.body().contains("limit must be positive"));
    }

    @Test
    void healthHasFeaturePlantStats() throws Exception {
        JsonNode body = getJson("/health");
        assertEquals("ok", body.path("status").asText());
        assertEquals("frontend-adapter", body.path("service").asText());
        assertEquals("modules", body.path("feature_source").asText());
        assertEquals("loaded", body.path("market_metadata").path("status").asText());
        assertEquals(2, body.path("market_metadata").path("markets").asInt());
        assertFalse(body.path("feature_output_refresh").path("enabled").asBoolean());
        assertEquals(42L, body.path("feature_plant").path("events_in").asLong());
        assertEquals(17L, body.path("feature_plant").path("events_out").asLong());
        assertTrue(body.path("store").path("sequence").asLong() > 0L);
        assertEquals(0L, body.path("quote_updates").path("timeouts").asLong());
        assertEquals(0L, body.path("quote_updates").path("rejected").asLong());
        assertEquals(4, body.path("quote_updates").path("max_waits").asInt());
    }

    @Test
    void healthReportsFeatureOutputRefreshStatus() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://unused/kalshi"
        ));
        server = new FrontendAdapterServer(
            config,
            new FrontendFeatureStore(100, 100),
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            () -> new FeatureOutputRefreshStatus(
                true,
                true,
                Instant.parse("2026-05-20T00:00:01Z"),
                Instant.parse("2026-05-20T00:00:02Z"),
                "db unavailable",
                7,
                11L,
                2L
            )
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode refresh = getJson("/health").path("feature_output_refresh");

        assertTrue(refresh.path("enabled").asBoolean());
        assertTrue(refresh.path("running").asBoolean());
        assertEquals("2026-05-20T00:00:01Z", refresh.path("last_success_at").asText());
        assertEquals("db unavailable", refresh.path("last_error").asText());
        assertEquals(7, refresh.path("last_row_count").asInt());
        assertEquals(11L, refresh.path("total_loaded").asLong());
        assertEquals(2L, refresh.path("refresh_errors").asLong());
    }

    @Test
    void quotesReflectFeatureOutputRefreshWithoutServerRestart() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://unused/kalshi",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS", "10"
        ));
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        AtomicInteger reads = new AtomicInteger();
        FeatureOutputRefreshService refresh = new FeatureOutputRefreshService(config, store, request -> {
            return switch (reads.getAndIncrement()) {
                case 0 -> List.of(row("feature-1", "2026-05-20T00:00:01Z", 1_000L, "seed", 500_000L));
                case 1 -> List.of(row("feature-2", "2026-05-20T00:00:02Z", 2_000L, "refresh", 600_000L));
                default -> List.of();
            };
        });
        refresh.seedOnce();
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            refresh::status
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        assertEquals(500_000L, getJson("/quotes?symbols=MKT-1")
            .path("quotes").get(0).path("midpoint_micros").asLong());

        refresh.refreshOnce();

        JsonNode quote = getJson("/quotes?symbols=MKT-1").path("quotes").get(0);
        assertEquals(600_000L, quote.path("midpoint_micros").asLong());
        assertEquals(2_000L, quote.path("event_ts_ms").asLong());
        assertEquals(2L, getJson("/health").path("feature_output_refresh").path("total_loaded").asLong());
    }

    @Test
    void quotesIgnoreOlderFeatureOutputRowsAcceptedAfterNewerRows() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://unused/kalshi",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS", "10"
        ));
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        AtomicInteger reads = new AtomicInteger();
        FeatureOutputRefreshService refresh = new FeatureOutputRefreshService(config, store, request -> {
            return switch (reads.getAndIncrement()) {
                case 0 -> List.of(row("feature-2", "2026-05-20T00:00:01Z", 2_000L, "seed-new", 600_000L));
                case 1 -> List.of(row("feature-1", "2026-05-20T00:00:02Z", 1_000L, "replay-old", 500_000L));
                default -> List.of();
            };
        });
        refresh.seedOnce();
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            refresh::status
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        refresh.refreshOnce();

        JsonNode quote = getJson("/quotes?symbols=MKT-1").path("quotes").get(0);
        assertEquals(600_000L, quote.path("midpoint_micros").asLong());
        assertEquals(2_000L, quote.path("event_ts_ms").asLong());
        assertEquals("seed-new", quote.path("source_event_id").asText());
        assertEquals(2L, getJson("/health").path("feature_output_refresh").path("total_loaded").asLong());
    }

    @Test
    void quoteUpdatesWakeWhenFeatureOutputRefreshAcceptsRows() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://unused/kalshi",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS", "10"
        ));
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        AtomicInteger reads = new AtomicInteger();
        FeatureOutputRefreshService refresh = new FeatureOutputRefreshService(config, store, request -> {
            return switch (reads.getAndIncrement()) {
                case 0 -> List.of(row("feature-1", "2026-05-20T00:00:01Z", 1_000L, "seed", 500_000L));
                case 1 -> List.of(row("feature-2", "2026-05-20T00:00:02Z", 2_000L, "refresh", 600_000L));
                default -> List.of();
            };
        });
        refresh.seedOnce();
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            refresh::status
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
        long sequence = getJson("/quotes?symbols=MKT-1").path("sequence").asLong();

        CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
            HttpRequest.newBuilder(URI.create(baseUrl
                + "/quotes/updates?symbols=MKT-1&after=" + sequence + "&timeout_ms=1000")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        Thread.sleep(25L);
        assertFalse(pending.isDone());

        refresh.refreshOnce();

        HttpResponse<String> response = pending.get(2, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        JsonNode body = MAPPER.readTree(response.body());
        assertTrue(body.path("changed").asBoolean());
        assertTrue(body.path("sequence").asLong() > sequence);
        JsonNode quote = body.path("quotes").get(0);
        assertEquals(600_000L, quote.path("midpoint_micros").asLong());
        assertEquals("refresh", quote.path("source_event_id").asText());
    }

    @Test
    void quoteUpdatesWakeOnOlderFeatureOutputButReturnCurrentLatestQuote() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://unused/kalshi",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS", "10"
        ));
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        AtomicInteger reads = new AtomicInteger();
        FeatureOutputRefreshService refresh = new FeatureOutputRefreshService(config, store, request -> {
            return switch (reads.getAndIncrement()) {
                case 0 -> List.of(row("feature-2", "2026-05-20T00:00:01Z", 2_000L, "seed-new", 600_000L));
                case 1 -> List.of(row("feature-1", "2026-05-20T00:00:02Z", 1_000L, "replay-old", 500_000L));
                default -> List.of();
            };
        });
        refresh.seedOnce();
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            refresh::status
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
        long sequence = getJson("/quotes?symbols=MKT-1").path("sequence").asLong();

        CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
            HttpRequest.newBuilder(URI.create(baseUrl
                + "/quotes/updates?symbols=MKT-1&after=" + sequence + "&timeout_ms=1000")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        Thread.sleep(25L);
        assertFalse(pending.isDone());

        refresh.refreshOnce();

        HttpResponse<String> response = pending.get(2, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        JsonNode body = MAPPER.readTree(response.body());
        assertTrue(body.path("changed").asBoolean());
        JsonNode quote = body.path("quotes").get(0);
        assertEquals(600_000L, quote.path("midpoint_micros").asLong());
        assertEquals(2_000L, quote.path("event_ts_ms").asLong());
        assertEquals("seed-new", quote.path("source_event_id").asText());
    }

    @Test
    void metricsExposesRequiredKeys() throws Exception {
        getJson("/symbols");
        HttpResponse<String> metrics = get("/metrics");
        String body = metrics.body();
        assertTrue(body.contains("frontend_adapter_symbols"));
        assertTrue(body.contains("frontend_adapter_features_total"));
        assertTrue(body.contains("frontend_adapter_store_sequence"));
        assertTrue(body.contains("frontend_adapter_quote_update_requests_total"));
        assertTrue(body.contains("frontend_adapter_quote_update_timeouts_total"));
        assertTrue(body.contains("frontend_adapter_quote_update_rejected_total"));
        assertTrue(body.contains("frontend_adapter_quote_update_active"));
        assertTrue(body.contains("frontend_adapter_http_requests_total{path=\"/symbols\"}"));
        assertTrue(body.contains("frontend_adapter_http_request_duration_seconds_sum{path=\"/symbols\"}"));
        assertTrue(body.contains("frontend_adapter_http_request_duration_seconds_count{path=\"/symbols\"}"));
    }

    @Test
    void corsHeadersAndOptionsPreflight() throws Exception {
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/datafeed/config")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        HttpResponse<String> preflight = client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/datafeed/config"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(204, preflight.statusCode());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), "for " + path + ": " + response.body());
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> getWithTimeout(String path, long timeoutMs) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private static void seed(FrontendFeatureStore store) {
        for (long ts = 1_000L; ts <= 5_000L; ts += 500L) {
            store.accept(new FeatureOutput(
                FrontendFeatureStore.BBO_FEATURE,
                FrontendFeatureStore.BBO_FEATURE,
                "MKT-1",
                ts,
                "evt-" + ts,
                Map.of(
                    "bid_price_micros", 450_000L + ts,
                    "ask_price_micros", 550_000L + ts,
                    "midpoint_micros", 500_000L + ts
                )
            ));
        }
    }

    private static MarketMetadata metadata(String ticker, String eventTicker, String seriesTicker, String status) {
        return new MarketMetadata(
            ticker,
            eventTicker,
            seriesTicker,
            status,
            Instant.parse("2026-05-20T01:00:00Z"),
            Instant.parse("2026-05-21T01:00:00Z"),
            null,
            "{\"rule\":\"value\"}",
            "{\"ticker\":\"" + ticker + "\"}"
        );
    }

    private static FeatureOutputRow row(
        String eventId,
        String createdAt,
        long eventTsMs,
        String sourceEventId,
        long midpointMicros
    ) {
        return new FeatureOutputRow(
            eventId,
            Instant.parse(createdAt),
            new FeatureOutput(
                FrontendFeatureStore.BBO_FEATURE,
                FrontendFeatureStore.BBO_FEATURE,
                "MKT-1",
                eventTsMs,
                sourceEventId,
                Map.of(
                    "bid_price_micros", midpointMicros - 10_000L,
                    "ask_price_micros", midpointMicros + 10_000L,
                    "midpoint_micros", midpointMicros
                )
            )
        );
    }
}
