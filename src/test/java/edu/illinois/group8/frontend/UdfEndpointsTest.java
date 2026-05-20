package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.storage.db.FeatureOutputRow;
import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.OperatorPipelineStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
    void marketsEndpointCombinesSearchStatusAndLimitFilters() throws Exception {
        JsonNode closedMatch = getJson("/markets?query=EVENT-META&status=closed&limit=10");

        assertEquals(1, closedMatch.path("count").asInt());
        assertEquals("MKT-META", closedMatch.path("markets").get(0).path("market_ticker").asText());

        JsonNode openMiss = getJson("/markets?query=EVENT-META&status=open&limit=10");

        assertEquals(0, openMiss.path("count").asInt());
        assertEquals(0, openMiss.path("markets").size());
    }

    @Test
    void marketCatalogHidesSmokeByDefaultAndAllowsExplicitOverride() throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(feature("MKT-LIVE", System.currentTimeMillis() - 250L, "live-source", 500_000L));
        store.accept(feature(
            "LIVE-PRODUCT-SMOKE-run-1",
            System.currentTimeMillis() - 100L,
            "live-product-smoke-run-1-bbo-001",
            600_000L
        ));
        store.accept(feature(
            "DEMO-DBPRIMARY-26MAY19-T50",
            System.currentTimeMillis() - 150L,
            "demo-db-primary-canonical-bbo-001",
            550_000L
        ));
        server = new FrontendAdapterServer(
            FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0")),
            store,
            FrontendMarketMetadataCatalog.loaded("test", List.of(
                metadata("MKT-LIVE", "EVENT-LIVE", "SERIES-LIVE", "open"),
                metadata("DEMO-DBPRIMARY-26MAY19-T50", "DEMO-DBPRIMARY-26MAY19", "DEMO-DBPRIMARY", "open"),
                metadata("LIVE-PRODUCT-SMOKE-run-1", "LIVE-PRODUCT-SMOKE", "LIVE-PRODUCT-SMOKE", "open")
            )),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode markets = getJson("/markets?limit=10");
        assertEquals(2, markets.path("count").asInt());
        assertTrue(containsMarket(markets.path("markets"), "MKT-LIVE"));
        assertTrue(containsMarket(markets.path("markets"), "DEMO-DBPRIMARY-26MAY19-T50"));
        JsonNode demoSearch = getJson("/datafeed/search?query=DEMO-DBPRIMARY&limit=10");
        assertEquals(1, demoSearch.size());
        assertEquals("demo", demoSearch.get(0).path("source_kind").asText());
        assertTrue(demoSearch.get(0).path("synthetic").asBoolean());
        JsonNode demoSymbols = getJson("/symbols");
        assertTrue(containsSymbol(demoSymbols.path("symbols"), "DEMO-DBPRIMARY-26MAY19-T50"));
        JsonNode smokeHiddenSearch = getJson("/datafeed/search?query=LIVE-PRODUCT-SMOKE&limit=10");
        assertEquals(0, smokeHiddenSearch.size());
        JsonNode smokeHiddenSymbols = getJson("/symbols");
        assertFalse(containsSymbol(smokeHiddenSymbols.path("symbols"), "LIVE-PRODUCT-SMOKE-run-1"));

        JsonNode smokeMarkets = getJson("/markets?include_smoke=true&limit=10");
        assertEquals(3, smokeMarkets.path("count").asInt());
        assertTrue(containsMarket(smokeMarkets.path("markets"), "LIVE-PRODUCT-SMOKE-run-1"));
        JsonNode smokeSearch = getJson("/datafeed/search?query=LIVE-PRODUCT-SMOKE&include_smoke=true&limit=10");
        assertEquals(1, smokeSearch.size());
        assertEquals("smoke", smokeSearch.get(0).path("source_kind").asText());
        assertTrue(smokeSearch.get(0).path("synthetic").asBoolean());
        JsonNode smokeSymbols = getJson("/symbols?include_smoke=true");
        assertTrue(containsSymbol(smokeSymbols.path("symbols"), "LIVE-PRODUCT-SMOKE-run-1"));
    }

    @Test
    void directSmokeQuotesAndFeaturesRemainAvailable() throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        String market = "LIVE-PRODUCT-SMOKE-run-1";
        store.accept(feature(market, System.currentTimeMillis() - 100L, "live-product-smoke-run-1-bbo-001", 600_000L));
        server = new FrontendAdapterServer(
            FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0")),
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode quote = getJson("/quotes?symbols=" + market).path("quotes").get(0);
        assertEquals(market, quote.path("symbol").asText());
        assertEquals("smoke", quote.path("source_kind").asText());
        assertTrue(quote.path("synthetic").asBoolean());

        JsonNode features = getJson("/features?symbol=" + market + "&feature=feature.bbo&limit=10");
        assertEquals(1, features.path("count").asInt());
        assertEquals("smoke", features.path("outputs").get(0).path("source_kind").asText());
        assertTrue(features.path("outputs").get(0).path("synthetic").asBoolean());
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
        assertTrue(root.body().contains("id=\"release-identity\""));
        assertTrue(root.body().contains("id=\"health-data-age\""));
        assertTrue(root.body().contains("id=\"quote-update-health\""));
        assertTrue(root.body().contains("id=\"market-search\""));
        assertTrue(root.body().contains("id=\"market-status-filter\""));
        assertTrue(root.body().contains("id=\"market-search-apply\""));
        assertTrue(root.body().contains("id=\"market-state\""));
        assertTrue(root.body().contains("id=\"product-market-panel\""));
        assertTrue(root.body().contains("id=\"research-features-panel\""));
        assertTrue(root.body().contains("id=\"runtime-operator-panel\""));
        assertTrue(root.body().contains("id=\"latency-freshness-panel\""));
        assertTrue(root.body().contains("id=\"operator-plan-panel\""));
        assertTrue(root.body().contains("Operator Control"));
        assertTrue(root.body().contains("id=\"operator-control-enabled\""));
        assertTrue(root.body().contains("id=\"operator-private-key-pem-present\""));
        assertTrue(root.body().contains("id=\"operator-db-password-present\""));
        assertTrue(root.body().contains("id=\"operator-command-plan\""));
        assertTrue(root.body().contains("<dt>Release</dt>"));
        assertTrue(root.body().contains("<dt>Data age</dt>"));
        assertTrue(root.body().contains("<dt>Quote feed</dt>"));
        assertTrue(root.body().contains("placeholder=\"same origin\""));
        assertTrue(index.body().contains("<link rel=\"stylesheet\" href=\"styles.css\" />"));
        assertTrue(index.body().contains(
            "<script src=\"vendor/lightweight-charts-4.2.0.standalone.production.js\"></script>"));
        assertTrue(index.body().contains("<script src=\"app.js\"></script>"));
        assertFalse(index.body().contains("https://unpkg.com"));
        assertFalse(index.body().contains("cdn.jsdelivr"));
        assertFalse(index.body().contains("cdnjs"));
    }

    @Test
    void servesTradingViewJavascriptAndStyles() throws Exception {
        HttpResponse<String> js = get("/app.js");
        HttpResponse<String> css = get("/styles.css");
        HttpResponse<String> chart = get("/vendor/lightweight-charts-4.2.0.standalone.production.js");

        assertEquals(200, js.statusCode());
        assertEquals(200, css.statusCode());
        assertEquals(200, chart.statusCode());
        assertTrue(js.headers().firstValue("content-type").orElse("").contains("text/javascript"));
        assertTrue(css.headers().firstValue("content-type").orElse("").contains("text/css"));
        assertTrue(chart.headers().firstValue("content-type").orElse("").contains("text/javascript"));
        assertTrue(js.body().contains("/quotes/updates?symbols="));
        assertTrue(js.body().contains("MARKET_CATALOG_LIMIT"));
        assertTrue(js.body().contains("'/markets?' + params.join('&')"));
        assertTrue(js.body().contains("market-search"));
        assertTrue(js.body().contains("market-status-filter"));
        assertTrue(js.body().contains("market-search-apply"));
        assertTrue(js.body().contains("market-state"));
        assertTrue(js.body().contains("markets.markets.length > 0"));
        assertTrue(js.body().contains("/features?symbol="));
        assertTrue(js.body().contains("/health"));
        assertTrue(js.body().contains("document.getElementById('release-identity')"));
        assertTrue(js.body().contains("document.getElementById('health-data-age')"));
        assertTrue(js.body().contains("document.getElementById('quote-update-health')"));
        assertTrue(js.body().contains("document.getElementById('product-readiness-state')"));
        assertTrue(js.body().contains("document.getElementById('runtime-feature-source')"));
        assertTrue(js.body().contains("document.getElementById('freshness-age-ms')"));
        assertTrue(js.body().contains("document.getElementById('operator-env-plan')"));
        assertTrue(js.body().contains("document.getElementById('operator-control-enabled')"));
        assertTrue(js.body().contains("document.getElementById('operator-command-plan')"));
        assertTrue(js.body().contains("body.release"));
        assertTrue(js.body().contains("body.data_freshness"));
        assertTrue(js.body().contains("body.quote_streams"));
        assertTrue(js.body().contains("/operator/status"));
        assertTrue(js.body().contains("/operator/plan"));
        assertTrue(js.body().contains("body.product_readiness"));
        assertTrue(js.body().contains("generateOperatorPlan"));
        assertTrue(js.body().contains("quote_updates"));
        assertTrue(js.body().contains("EventSource"));
        assertTrue(js.body().contains("/quotes/stream?symbols="));
        assertTrue(js.body().contains("long-poll timeout"));
        assertTrue(js.body().contains("long-poll fallback"));
        assertTrue(js.body().contains("fallback polling"));
        assertTrue(js.body().contains("latest_event_ts_ms"));
        assertTrue(js.body().contains("nextSequence < quoteSequence"));
        assertTrue(js.body().contains("window.location.origin"));
        assertTrue(css.body().contains("chart-container"));
        assertTrue(chart.body().contains("LightweightCharts"));
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
    void basicAuthDisabledByDefaultAllowsStaticAndApi() throws Exception {
        assertEquals(200, get("/").statusCode());
        assertEquals(200, get("/health").statusCode());
        assertEquals(200, get("/markets?limit=10").statusCode());
    }

    @Test
    void basicAuthProtectsStaticAndApi() throws Exception {
        restartWithBasicAuth();

        HttpResponse<String> root = get("/");
        HttpResponse<String> health = get("/health");
        HttpResponse<String> markets = get("/markets?limit=10");

        assertEquals(401, root.statusCode());
        assertEquals(401, health.statusCode());
        assertEquals(401, markets.statusCode());
        assertTrue(root.headers().firstValue("WWW-Authenticate").orElse("").contains("Basic"));
        assertTrue(health.headers().firstValue("WWW-Authenticate").orElse("").contains("Basic"));
    }

    @Test
    void basicAuthAcceptsValidCredentialsAndRejectsBadCredentials() throws Exception {
        restartWithBasicAuth();

        assertEquals(401, getWithBasicAuth("/health", "operator", "wrong").statusCode());
        assertEquals(200, getWithBasicAuth("/", "operator", "secret").statusCode());
        assertEquals(200, getWithBasicAuth("/health", "operator", "secret").statusCode());
        assertEquals(200, getWithBasicAuth("/features?symbol=MKT-1&feature=feature.bbo&limit=1",
            "operator", "secret").statusCode());
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
    void quoteStreamSendsSnapshotThenPushesChangedQuotes() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0"));
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(feature("MKT-1", 1_000L, "seed", 500_000L));
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        try (SseStream stream = openSse("/quotes/stream?symbols=MKT-1")) {
            JsonNode snapshot = stream.readJsonEvent();
            assertFalse(snapshot.path("changed").asBoolean());
            assertEquals(1L, snapshot.path("sequence").asLong());
            assertEquals(1, snapshot.path("quotes").size());
            assertEquals(500_000L, snapshot.path("quotes").get(0).path("midpoint_micros").asLong());

            store.accept(feature("MKT-1", 2_000L, "refresh", 600_000L));

            JsonNode changed = stream.readJsonEvent();
            assertTrue(changed.path("changed").asBoolean());
            assertEquals(2L, changed.path("sequence").asLong());
            assertEquals(600_000L, changed.path("quotes").get(0).path("midpoint_micros").asLong());
            assertEquals("refresh", changed.path("quotes").get(0).path("source_event_id").asText());
            assertFalse(changed.path("server_ts_ms").isMissingNode());
        }
    }

    @Test
    void quoteStreamRejectsWhenSlotsAreExhausted() throws Exception {
        List<SseStream> streams = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                streams.add(openSse("/quotes/stream?symbols=MKT-1"));
            }

            HttpResponse<String> rejected = get("/quotes/stream?symbols=MKT-1");

            assertEquals(429, rejected.statusCode());
            assertTrue(rejected.body().contains("too many active quote streams"));
            JsonNode health = getJson("/health");
            assertEquals(2L, health.path("quote_streams").path("active_streams").asLong());
            assertEquals(2, health.path("quote_streams").path("max_streams").asInt());
            assertTrue(health.path("quote_streams").path("rejected").asLong() >= 1L);

            streams.remove(0).close();
            JsonNode released = waitForQuoteStreamActiveAtMost(1L);
            assertTrue(released.path("quote_streams").path("active_streams").asLong() <= 1L);
            try (SseStream replacement = openSse("/quotes/stream?symbols=MKT-1")) {
                assertEquals(1L, replacement.readJsonEvent().path("quotes").size());
            }
        } finally {
            for (SseStream stream : streams) {
                stream.close();
            }
        }
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
        assertFalse(body.path("release").isMissingNode());
        assertEquals(5_000L, body.path("data_freshness").path("latest_event_ts_ms").asLong());
        assertEquals("MKT-1", body.path("data_freshness").path("symbol").asText());
        assertEquals("feature.bbo", body.path("data_freshness").path("feature_name").asText());
        assertEquals("evt-5000", body.path("data_freshness").path("source_event_id").asText());
        assertEquals("live", body.path("data_freshness").path("source_kind").asText());
        assertFalse(body.path("data_freshness").path("synthetic").asBoolean());
        assertTrue(body.path("data_freshness").path("live_data_observed").asBoolean());
        assertTrue(body.path("data_freshness").path("latest_event_age_ms").asLong() >= 0L);
        assertEquals("stale", body.path("product_readiness").path("status").asText());
        assertTrue(body.path("product_readiness").path("stale").asBoolean());
        assertTrue(body.path("product_readiness").path("reasons").toString().contains("stale_feature_output"));
        assertEquals(0L, body.path("quote_updates").path("timeouts").asLong());
        assertEquals(0L, body.path("quote_updates").path("rejected").asLong());
        assertEquals(4, body.path("quote_updates").path("max_waits").asInt());
        assertEquals(0L, body.path("quote_streams").path("rejected").asLong());
        assertEquals(2, body.path("quote_streams").path("max_streams").asInt());
        assertEquals("disabled", body.path("operator_pipeline").path("status").asText());
    }

    @Test
    void healthReportsOperatorPipelineStatus() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0"));
        server = new FrontendAdapterServer(
            config,
            new FrontendFeatureStore(100, 100),
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            () -> new OperatorPipelineStatus("degraded", "featureplant-prod", 8L, 13L, 5L, 7L, 250L, null),
            FrontendReleaseInfo.empty()
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode pipeline = getJson("/health").path("operator_pipeline");

        assertEquals("degraded", pipeline.path("status").asText());
        assertEquals("featureplant-prod", pipeline.path("cursor_name").asText());
        assertEquals(8L, pipeline.path("cursor_commit_seq").asLong());
        assertEquals(13L, pipeline.path("canonical_max_commit_seq").asLong());
        assertEquals(5L, pipeline.path("cursor_lag_events").asLong());
        assertEquals(7L, pipeline.path("latest_market_state_commit_seq").asLong());
        assertEquals(250L, pipeline.path("latest_state_age_ms").asLong());
    }

    @Test
    void healthRedactsOperatorVisibleErrors() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0"));
        String sensitive = "jdbc:postgresql://user:secret@db/kalshi?password=hunter2 Authorization: Basic abcdef "
            + "-----BEGIN PRIVATE KEY-----secret-key-----END PRIVATE KEY-----";
        server = new FrontendAdapterServer(
            config,
            new FrontendFeatureStore(100, 100),
            FrontendMarketMetadataCatalog.unavailable("db", sensitive),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            () -> new FeatureOutputRefreshStatus(
                true,
                false,
                null,
                Instant.parse("2026-05-20T00:00:02Z"),
                sensitive,
                0,
                0L,
                1L
            ),
            () -> OperatorPipelineStatus.unavailable("featureplant-prod", sensitive),
            FrontendReleaseInfo.empty()
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        String body = get("/health").body();

        assertFalse(body.contains("hunter2"));
        assertFalse(body.contains("secret@"));
        assertFalse(body.contains("abcdef"));
        assertFalse(body.contains("secret-key"));
        assertTrue(body.contains("[redacted]"));
    }

    @Test
    void operatorStatusReportsRedactedRuntimeConfiguration() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://user:secret@db/kalshi?password=hunter2",
            "FRONTEND_ADAPTER_DB_USER", "frontend",
            "FRONTEND_ADAPTER_DB_PASSWORD", "db-secret",
            "FRONTEND_ADAPTER_OPERATOR_CONTROL_ENABLED", "true"
        ));
        server = new FrontendAdapterServer(
            config,
            new FrontendFeatureStore(100, 100),
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            () -> new OperatorPipelineStatus("ok", "cursor", 7L, 7L, 0L, 7L, 10L, null),
            new FrontendReleaseInfo("abcdef", "kalshi-project:abcdef", "live-product", "1", "1")
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        String body = get("/operator/status").body();
        JsonNode status = MAPPER.readTree(body);

        assertEquals("ok", status.path("status").asText());
        assertTrue(status.path("operator_control").path("enabled").asBoolean());
        assertFalse(status.path("operator_control").path("post_allowed").asBoolean());
        assertEquals("live-product", status.path("release").path("profile").asText());
        assertEquals("ok", status.path("pipeline").path("status").asText());
        assertTrue(status.path("configuration").path("db").path("url_configured").asBoolean());
        assertTrue(status.path("configuration").path("db").path("password_configured").asBoolean());
        assertFalse(body.contains("hunter2"));
        assertFalse(body.contains("secret@"));
        assertFalse(body.contains("db-secret"));
        assertTrue(body.contains("[redacted]"));
    }

    @Test
    void operatorPlanPostIsDisabledByDefault() throws Exception {
        HttpResponse<String> response = postJson("/operator/plan", "{\"profile\":\"live-product\"}");

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("operator control POST is disabled"));
    }

    @Test
    void operatorPlanEnabledStillRequiresBasicAuth() throws Exception {
        restartWithOperatorControl(false);

        HttpResponse<String> response = postJson("/operator/plan", "{\"profile\":\"long-replay-demo\"}");

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("requires Basic Auth"));
    }

    @Test
    void operatorPlanValidatesLiveProfileAndRedactsSecrets() throws Exception {
        restartWithOperatorControl(true);
        String request = """
            {
              "profile": "live-product",
              "kalshi": {
                "key_id": "key-123",
                "private_key_pem": "-----BEGIN PRIVATE KEY-----secret-key-----END PRIVATE KEY-----"
              },
              "db": {
                "url": "jdbc:postgresql://user:secret@db/kalshi?password=hunter2",
                "user": "frontend",
                "password": "db-secret"
              },
              "release": {"image": "kalshi-project:bd"}
            }
            """;

        HttpResponse<String> response = postJsonWithBasicAuth("/operator/plan", request, "operator", "secret");
        JsonNode body = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertFalse(body.path("can_deploy").asBoolean());
        assertEquals("blocked", body.path("status").asText());
        assertFalse(checkPassed(body, "basic_auth"));
        String raw = response.body();
        assertFalse(raw.contains("key-123"));
        assertFalse(raw.contains("hunter2"));
        assertFalse(raw.contains("secret@"));
        assertFalse(raw.contains("db-secret"));
        assertFalse(raw.contains("secret-key"));
        assertEquals("<set via secret>", body.path("redacted_env").path("DB_WRITER_DATABASE_PASSWORD").asText());
    }

    @Test
    void operatorPlanReadyWhenRequiredLiveInputsArePresent() throws Exception {
        restartWithOperatorControl(true);
        String request = """
            {
              "profile": "live-product",
              "kalshi": {"key_id": "key-123", "private_key_path": "/run/secrets/kalshi_private_key"},
              "db": {"url": "jdbc:postgresql://db/kalshi", "user": "frontend", "password_present": true},
              "basic_auth": {"user": "operator", "password_present": true},
              "release": {"ref": "abcdef123456"}
            }
            """;

        JsonNode body = MAPPER.readTree(postJsonWithBasicAuth(
            "/operator/plan",
            request,
            "operator",
            "secret"
        ).body());

        assertTrue(body.path("can_deploy").asBoolean());
        assertEquals("ready", body.path("status").asText());
        assertTrue(checkPassed(body, "basic_auth"));
        assertEquals("docker compose --env-file .env --profile live-product up -d",
            body.path("commands").get(1).asText());
    }

    @Test
    void operatorPlanIsProtectedByBasicAuthWhenConfigured() throws Exception {
        restartWithOperatorControl(true);
        String request = "{\"profile\":\"long-replay-demo\"}";

        assertEquals(401, postJson("/operator/plan", request).statusCode());
        assertEquals(200, postJsonWithBasicAuth("/operator/plan", request, "operator", "secret").statusCode());
    }

    @Test
    void healthReportsReleaseIdentityAndDataFreshness() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0"));
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(new FeatureOutput(
            FrontendFeatureStore.BBO_FEATURE,
            FrontendFeatureStore.BBO_FEATURE,
            "MKT-REL",
            System.currentTimeMillis() - 250L,
            "evt-release",
            Map.of(
                "bid_price_micros", 450_000L,
                "ask_price_micros", 550_000L,
                "midpoint_micros", 500_000L
            )
        ));
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            OperatorPipelineStatus::disabled,
            new FrontendReleaseInfo("abcdef1234567890", "kalshi-project:abcdef", "live-product", "261", "2")
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode body = getJson("/health");

        assertEquals("abcdef1234567890", body.path("release").path("sha").asText());
        assertEquals("kalshi-project:abcdef", body.path("release").path("image").asText());
        assertEquals("live-product", body.path("release").path("profile").asText());
        assertEquals("261", body.path("release").path("run_id").asText());
        assertEquals("2", body.path("release").path("run_attempt").asText());
        JsonNode freshness = body.path("data_freshness");
        assertEquals("MKT-REL", freshness.path("symbol").asText());
        assertEquals("feature.bbo", freshness.path("feature_name").asText());
        assertEquals("evt-release", freshness.path("source_event_id").asText());
        assertEquals("live", freshness.path("source_kind").asText());
        assertFalse(freshness.path("synthetic").asBoolean());
        assertTrue(freshness.path("live_data_observed").asBoolean());
        assertEquals(1L, freshness.path("store_sequence").asLong());
        assertTrue(freshness.path("latest_event_age_ms").asLong() >= 0L);
        JsonNode readiness = body.path("product_readiness");
        assertEquals("ok", readiness.path("status").asText());
        assertFalse(readiness.path("stale").asBoolean());
        assertFalse(readiness.path("degraded").asBoolean());
        assertEquals(15_000L, readiness.path("stale_after_ms").asLong());
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

        JsonNode body = getJson("/health");
        JsonNode refresh = body.path("feature_output_refresh");

        assertTrue(refresh.path("enabled").asBoolean());
        assertTrue(refresh.path("running").asBoolean());
        assertEquals("2026-05-20T00:00:01Z", refresh.path("last_success_at").asText());
        assertEquals("db unavailable", refresh.path("last_error").asText());
        assertEquals(7, refresh.path("last_row_count").asInt());
        assertEquals(11L, refresh.path("total_loaded").asLong());
        assertEquals(2L, refresh.path("refresh_errors").asLong());
        JsonNode freshness = body.path("data_freshness");
        assertEquals("unknown", freshness.path("source_kind").asText());
        assertFalse(freshness.path("synthetic").asBoolean());
        assertFalse(freshness.path("live_data_observed").asBoolean());
        assertEquals("stale", body.path("product_readiness").path("status").asText());
        assertTrue(body.path("product_readiness").path("reasons").toString().contains("no_feature_output"));
        assertTrue(body.path("product_readiness").path("reasons").toString().contains("feature_refresh_errors"));
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
    void healthMarksSmokeOnlyFreshnessAsSyntheticAndDegraded() throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(feature(
            "LIVE-PRODUCT-SMOKE-run-1",
            System.currentTimeMillis() - 100L,
            "live-product-smoke-run-1-bbo-001",
            600_000L
        ));
        server = new FrontendAdapterServer(
            FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0")),
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode body = getJson("/health");
        JsonNode freshness = body.path("data_freshness");
        assertEquals("smoke", freshness.path("source_kind").asText());
        assertTrue(freshness.path("synthetic").asBoolean());
        assertFalse(freshness.path("live_data_observed").asBoolean());
        JsonNode readiness = body.path("product_readiness");
        assertEquals("degraded", readiness.path("status").asText());
        assertFalse(readiness.path("stale").asBoolean());
        assertTrue(readiness.path("degraded").asBoolean());
        assertTrue(readiness.path("reasons").toString().contains("synthetic_freshness"));
    }

    @Test
    void healthMarksDemoReplayFreshnessAsSyntheticAndDegraded() throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(feature(
            "DEMO-DBPRIMARY-26MAY19-T50",
            System.currentTimeMillis() - 100L,
            "demo-db-primary-canonical-bbo-001",
            600_000L
        ));
        server = new FrontendAdapterServer(
            FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0")),
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode body = getJson("/health");
        JsonNode freshness = body.path("data_freshness");
        assertEquals("demo", freshness.path("source_kind").asText());
        assertTrue(freshness.path("synthetic").asBoolean());
        assertFalse(freshness.path("live_data_observed").asBoolean());
        JsonNode readiness = body.path("product_readiness");
        assertEquals("degraded", readiness.path("status").asText());
        assertFalse(readiness.path("stale").asBoolean());
        assertTrue(readiness.path("degraded").asBoolean());
        assertTrue(readiness.path("reasons").toString().contains("synthetic_freshness"));
    }

    @Test
    void healthPrefersLiveFreshnessOverNewerSmoke() throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(feature("MKT-LIVE", System.currentTimeMillis() - 250L, "live-source", 500_000L));
        store.accept(feature(
            "LIVE-PRODUCT-SMOKE-run-1",
            System.currentTimeMillis() - 100L,
            "live-product-smoke-run-1-bbo-001",
            600_000L
        ));
        server = new FrontendAdapterServer(
            FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0")),
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode body = getJson("/health");
        JsonNode freshness = body.path("data_freshness");
        assertEquals("MKT-LIVE", freshness.path("symbol").asText());
        assertEquals("live", freshness.path("source_kind").asText());
        assertFalse(freshness.path("synthetic").asBoolean());
        assertTrue(freshness.path("live_data_observed").asBoolean());
        assertEquals("ok", body.path("product_readiness").path("status").asText());
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
        assertTrue(body.contains("frontend_adapter_quote_stream_requests_total"));
        assertTrue(body.contains("frontend_adapter_quote_stream_events_total"));
        assertTrue(body.contains("frontend_adapter_quote_stream_rejected_total"));
        assertTrue(body.contains("frontend_adapter_quote_stream_active"));
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
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("POST"));
        assertFalse(preflight.headers().firstValue("WWW-Authenticate").isPresent());
    }

    @Test
    void basicAuthDoesNotChallengeCorsPreflight() throws Exception {
        restartWithBasicAuth();

        HttpResponse<String> preflight = client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/datafeed/config"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(204, preflight.statusCode());
        assertEquals("*", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertFalse(preflight.headers().firstValue("WWW-Authenticate").isPresent());
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

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> getWithBasicAuth(String path, String user, String password) throws Exception {
        String token = Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Basic " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> postJsonWithBasicAuth(
        String path,
        String body,
        String user,
        String password
    ) throws Exception {
        String token = Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Basic " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
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

    private JsonNode waitForQuoteStreamActiveAtMost(long expectedMax) throws Exception {
        JsonNode health = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            health = getJson("/health");
            if (health.path("quote_streams").path("active_streams").asLong() <= expectedMax) {
                return health;
            }
            Thread.sleep(25L);
        }
        assertNotNull(health);
        return health;
    }

    private SseStream openSse(String path) throws Exception {
        HttpResponse<InputStream> response = client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream()
        );
        assertEquals(200, response.statusCode(), "for " + path);
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/event-stream"));
        return new SseStream(response.body());
    }

    private void restartWithBasicAuth() throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        seed(store);
        server = new FrontendAdapterServer(
            FrontendAdapterConfig.from(Map.of(
                "FRONTEND_ADAPTER_PORT", "0",
                "FRONTEND_ADAPTER_BASIC_AUTH_USER", "operator",
                "FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD", "secret"
            )),
            store,
            FrontendMarketMetadataCatalog.loaded("test", List.of(
                metadata("MKT-1", "EVENT-1", "SERIES-1", "open"),
                metadata("MKT-META", "EVENT-META", "SERIES-META", "closed")
            )),
            () -> new FrontendAdapterServer.FeaturePlantStats(42L, 17L, 0L)
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
    }

    private void restartWithOperatorControl(boolean basicAuth) throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        seed(store);
        java.util.HashMap<String, String> env = new java.util.HashMap<>();
        env.put("FRONTEND_ADAPTER_PORT", "0");
        env.put("FRONTEND_ADAPTER_OPERATOR_CONTROL_ENABLED", "true");
        if (basicAuth) {
            env.put("FRONTEND_ADAPTER_BASIC_AUTH_USER", "operator");
            env.put("FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD", "secret");
        }
        server = new FrontendAdapterServer(
            FrontendAdapterConfig.from(env),
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
    }

    private static boolean checkPassed(JsonNode body, String id) {
        for (JsonNode check : body.path("checklist")) {
            if (id.equals(check.path("id").asText())) {
                return check.path("passed").asBoolean();
            }
        }
        return false;
    }

    private static boolean containsMarket(JsonNode markets, String marketTicker) {
        for (JsonNode market : markets) {
            if (marketTicker.equals(market.path("market_ticker").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSymbol(JsonNode symbols, String symbol) {
        for (JsonNode entry : symbols) {
            if (symbol.equals(entry.path("symbol").asText())) {
                return true;
            }
        }
        return false;
    }

    private static FeatureOutput feature(String market, long eventTsMs, String sourceEventId, long midpointMicros) {
        return new FeatureOutput(
            FrontendFeatureStore.BBO_FEATURE,
            FrontendFeatureStore.BBO_FEATURE,
            market,
            eventTsMs,
            sourceEventId,
            Map.of(
                "bid_price_micros", midpointMicros - 10_000L,
                "ask_price_micros", midpointMicros + 10_000L,
                "midpoint_micros", midpointMicros
            )
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

    private static final class SseStream implements AutoCloseable {
        private final InputStream input;
        private final BufferedReader reader;

        private SseStream(InputStream input) {
            this.input = input;
            this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        }

        private JsonNode readJsonEvent() throws IOException {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        return MAPPER.readTree(data.toString());
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    String value = line.substring("data:".length());
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(value);
                }
            }
            throw new IOException("SSE stream ended before a data event");
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
