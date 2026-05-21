package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.backfill.CatalogSyncSummary;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.semantic.SemanticMetadataBatchSummary;
import edu.illinois.group8.semantic.SemanticMetadataConfig;
import edu.illinois.group8.storage.db.FeatureOutputReadRequest;
import edu.illinois.group8.storage.db.FeatureOutputReader;
import edu.illinois.group8.storage.db.FeatureOutputRow;
import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.OperatorLatencyStatus;
import edu.illinois.group8.storage.db.OperatorPipelineStatus;
import edu.illinois.group8.storage.db.OperatorSemanticMetadataStatus;
import edu.illinois.group8.storage.db.ReplayDemoStatus;
import edu.illinois.group8.storage.db.SemanticMarketMetadataReadRequest;
import edu.illinois.group8.storage.db.SemanticMarketMetadataReader;
import edu.illinois.group8.storage.db.SemanticMarketMetadataRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

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
        assertEquals(1, body.path("total_count").asInt());
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
    void marketCapabilitiesEndpointSummarizesChartQuoteCatalogAndSemanticTruth() throws Exception {
        JsonNode body = getJson("/api/markets/capabilities?limit=10");

        assertEquals("ok", body.path("status").asText());
        assertEquals(1, body.path("count").asInt());
        assertEquals(1, body.path("total_count").asInt());
        JsonNode summary = body.path("summary");
        assertEquals(3, summary.path("total_assets").asInt());
        assertEquals(1, summary.path("display_eligible_count").asInt());
        assertEquals(2, summary.path("filtered_out_count").asInt());
        assertEquals(2, summary.path("chartable_count").asInt());
        assertEquals(2, summary.path("quote_count").asInt());
        assertEquals(3, summary.path("semantic_missing_count").asInt());
        assertEquals(1, summary.path("metadata_only_count").asInt());

        JsonNode market = findMarket(body.path("markets"), "MKT-ELIGIBLE");
        assertTrue(market.path("chartable").asBoolean());
        assertTrue(market.path("chartable_from_bbo").asBoolean());
        assertEquals("bbo", market.path("best_chart_source").asText());
        assertEquals("bbo_history_available", market.path("chart_reason").asText());
        assertTrue(market.path("display_eligible").asBoolean());
        assertTrue(market.path("bars_24h_count").asInt() >= 10);

        JsonNode rawCatalog = getJson("/api/markets/capabilities?include_ineligible=true&limit=10");
        JsonNode catalogOnly = findMarket(rawCatalog.path("markets"), "MKT-META");
        assertFalse(catalogOnly.path("chartable").asBoolean());
        assertFalse(catalogOnly.path("has_quote").asBoolean());
        assertEquals("catalog_only", catalogOnly.path("chart_reason").asText());
        assertEquals("missing", catalogOnly.path("semantic_status").asText());
    }

    @Test
    void marketCapabilitiesEndpointAppliesCapabilityFiltersAndOffset() throws Exception {
        JsonNode metadataOnly = getJson(
            "/api/markets/capabilities?capability=metadata_only&include_ineligible=true&limit=1&offset=0"
        );

        assertEquals(1, metadataOnly.path("count").asInt());
        assertEquals(1, metadataOnly.path("total_count").asInt());
        assertEquals(0, metadataOnly.path("offset").asInt());
        assertFalse(metadataOnly.path("has_more").asBoolean());
        assertEquals("MKT-META", metadataOnly.path("markets").get(0).path("market_ticker").asText());

        JsonNode quoteAvailable = getJson("/api/markets/capabilities?capability=quote_available&limit=10");
        assertEquals(1, quoteAvailable.path("count").asInt());
        assertEquals("MKT-ELIGIBLE", quoteAvailable.path("markets").get(0).path("market_ticker").asText());
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
        assertEquals("bbo", body.path("source").asText());
    }

    @Test
    void datafeedHistoryFallsBackToTickerSnapshotBars() throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(new FeatureOutput(
            FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE,
            FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE,
            "MKT-TICKER",
            1_000L,
            "ticker-1",
            Map.of("price_micros", 420_000L)
        ));
        store.accept(new FeatureOutput(
            FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE,
            FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE,
            "MKT-TICKER",
            2_000L,
            "ticker-2",
            Map.of("yes_bid_micros", 430_000L, "yes_ask_micros", 450_000L)
        ));
        server = new FrontendAdapterServer(
            FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0")),
            store,
            FrontendMarketMetadataCatalog.loaded("test", List.of(
                metadata("MKT-TICKER", "EVENT-TICKER", "SERIES-TICKER", "indexed")
            )),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode symbols = getJson("/datafeed/symbols?symbol=MKT-TICKER");
        assertTrue(symbols.path("has_intraday").asBoolean());
        assertTrue(symbols.path("has_seconds").asBoolean());

        JsonNode body = getJson("/datafeed/history?symbol=MKT-TICKER&resolution=1S&from=0&to=10");
        assertEquals("ok", body.path("s").asText());
        assertEquals("ticker_snapshot", body.path("source").asText());
        assertEquals(2, body.path("t").size());
        assertEquals(0.42, body.path("c").get(0).asDouble(), 1e-9);
        assertEquals(0.44, body.path("c").get(1).asDouble(), 1e-9);
    }

    @Test
    void dbBackedHistoryReturnsTickerSnapshotBars() throws Exception {
        long startMs = System.currentTimeMillis() - 30 * 60_000L;
        String market = "MKT-DB-TICKER";
        FakeFeatureOutputReader reader = restartWithDbHistory(
            market,
            dbHistoryOutputs(
                market,
                FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE,
                startMs,
                "price_micros"
            )
        );

        JsonNode symbols = getJson("/datafeed/symbols?symbol=" + market);
        assertTrue(symbols.path("has_intraday").asBoolean());
        assertTrue(symbols.path("has_seconds").asBoolean());

        JsonNode body = getJson("/datafeed/history?symbol=" + market
            + "&resolution=1&from=" + ((startMs - 1_000L) / 1_000L)
            + "&to=" + ((startMs + 15 * 60_000L) / 1_000L));
        assertEquals("ok", body.path("s").asText());
        assertEquals("ticker_snapshot", body.path("source").asText());
        assertTrue(body.path("t").size() >= 10);
        assertTrue(reader.requests.stream().anyMatch(request ->
            market.equals(request.marketTicker())
                && request.featureNames().equals(List.of(FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE))
                && request.maxRows() == 10_000
        ));
    }

    @Test
    void dbBackedHistoryReturnsTradeTapeBars() throws Exception {
        long startMs = System.currentTimeMillis() - 30 * 60_000L;
        String market = "MKT-DB-TRADE";
        restartWithDbHistory(
            market,
            dbHistoryOutputs(
                market,
                FrontendFeatureStore.TRADE_TAPE_FEATURE,
                startMs,
                "yes_price_micros"
            )
        );

        JsonNode symbols = getJson("/datafeed/symbols?symbol=" + market);
        assertTrue(symbols.path("has_intraday").asBoolean());

        JsonNode body = getJson("/datafeed/history?symbol=" + market
            + "&resolution=1&from=" + ((startMs - 1_000L) / 1_000L)
            + "&to=" + ((startMs + 15 * 60_000L) / 1_000L));
        assertEquals("ok", body.path("s").asText());
        assertEquals("trade_tape", body.path("source").asText());
        assertTrue(body.path("t").size() >= 10);
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
        assertTrue(root.body().contains("id=\"market-capability-filter\""));
        assertTrue(root.body().contains("id=\"market-status-filter\""));
        assertTrue(root.body().contains("id=\"market-search-apply\""));
        assertTrue(root.body().contains("id=\"market-page-state\""));
        assertTrue(root.body().contains("id=\"chart-state\""));
        assertTrue(root.body().contains("id=\"market-state\""));
        assertTrue(root.body().contains("id=\"product-market-panel\""));
        assertTrue(root.body().contains("id=\"research-features-panel\""));
        assertTrue(root.body().contains("id=\"runtime-operator-panel\""));
        assertTrue(root.body().contains("id=\"latency-freshness-panel\""));
        assertTrue(root.body().contains("id=\"latency-throughput\""));
        assertTrue(root.body().contains("id=\"latency-ws-subscribed\""));
        assertTrue(root.body().contains("id=\"latency-db-queue-depth\""));
        assertTrue(root.body().contains("id=\"demo-signal-strip\""));
        assertTrue(root.body().contains("id=\"demo-signal-throughput\""));
        assertTrue(root.body().contains("id=\"operator-plan-panel\""));
        assertTrue(root.body().contains("id=\"live-dashboard-tab\""));
        assertTrue(root.body().contains("Live Dashboard"));
        assertTrue(root.body().contains("id=\"asset-explorer-tab\""));
        assertTrue(root.body().contains("Asset Explorer"));
        assertTrue(root.body().contains("id=\"semantic-tab\""));
        assertTrue(root.body().contains("Semantic Map"));
        assertTrue(root.body().contains("id=\"distribution-tab\""));
        assertTrue(root.body().contains("id=\"replay-tab\""));
        assertTrue(root.body().contains("id=\"operator-tab\""));
        assertTrue(root.body().contains("data-active-role=\"live\""));
        assertTrue(root.body().contains("id=\"trader-monitor-panel\""));
        assertTrue(root.body().contains("id=\"trader-bid\""));
        assertTrue(root.body().contains("id=\"trader-sse-status\""));
        assertTrue(root.body().contains("id=\"distribution-panel\""));
        assertTrue(root.body().contains("id=\"distribution-sample-payload\""));
        assertTrue(root.body().contains("id=\"research-feature-limit\""));
        assertTrue(root.body().contains("id=\"research-feature-window\""));
        assertTrue(root.body().contains("id=\"research-export-csv\""));
        assertTrue(root.body().contains("id=\"semantic-map-panel\""));
        assertTrue(root.body().contains("id=\"semantic-coverage-summary\""));
        assertTrue(root.body().contains("id=\"semantic-group-by\""));
        assertTrue(root.body().contains("id=\"semantic-render-mode\""));
        assertTrue(root.body().contains("id=\"semantic-drillup\""));
        assertTrue(root.body().contains("id=\"semantic-treemap\""));
        assertTrue(root.body().contains("id=\"operator-e2e-latency\""));
        assertTrue(root.body().contains("id=\"operator-pipeline-counts\""));
        assertTrue(root.body().contains("Operator Control"));
        assertTrue(root.body().contains("id=\"operator-control-enabled\""));
        assertTrue(root.body().contains("id=\"operator-private-key-pem-present\""));
        assertTrue(root.body().contains("id=\"operator-db-password-present\""));
        assertTrue(root.body().contains("id=\"operator-command-plan\""));
        assertTrue(root.body().contains("id=\"demo-orchestrator-panel\""));
        assertTrue(root.body().contains("id=\"demo-run-action\""));
        assertTrue(root.body().contains("id=\"demo-run-confirm-live\""));
        assertTrue(root.body().contains("id=\"demo-run-live-credentials\""));
        assertTrue(root.body().contains("value=\"live_credential_check\""));
        assertTrue(root.body().contains("value=\"live_catalog_sync_bounded\""));
        assertTrue(root.body().contains("value=\"s3_preflight_check\""));
        assertTrue(root.body().contains("id=\"demo-run-start\""));
        assertTrue(root.body().contains("id=\"replay-status-panel\""));
        assertTrue(root.body().contains("id=\"replay-status-projection\""));
        assertTrue(root.body().contains("Replay Trigger / Runbook"));
        assertTrue(root.body().contains("id=\"semantic-operator-panel\""));
        assertTrue(root.body().contains("id=\"semantic-run-start\""));
        assertTrue(root.body().contains("id=\"semantic-run-from-catalog\""));
        assertTrue(root.body().contains("id=\"semantic-run-max-tokens\""));
        assertTrue(root.body().contains("id=\"semantic-run-max-retries\""));
        assertTrue(root.body().contains("id=\"semantic-run-overwrite\""));
        assertTrue(root.body().contains("id=\"semantic-run-dry-run\" type=\"checkbox\" checked"));
        assertTrue(root.body().contains("<option value=\"active\">active</option>"));
        assertTrue(root.body().contains("id=\"semantic-run-openrouter-key\""));
        assertTrue(root.body().contains("id=\"catalog-sync-panel\""));
        assertTrue(root.body().contains("id=\"catalog-sync-series\""));
        assertTrue(root.body().contains("id=\"catalog-sync-status\""));
        assertTrue(root.body().contains("id=\"catalog-sync-limit\""));
        assertTrue(root.body().contains("id=\"catalog-sync-max-pages\""));
        assertTrue(root.body().contains("id=\"catalog-sync-max-tickers\""));
        assertTrue(root.body().contains("id=\"catalog-sync-dry-run\""));
        assertTrue(root.body().contains("id=\"catalog-sync-start\""));
        assertTrue(root.body().contains("Sync Catalog"));
        assertTrue(root.body().contains("data-role-panel=\"live,operator\""));
        assertTrue(root.body().contains("data-role-panel=\"replay,operator\""));
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
        HttpResponse<String> metricsHtml = get("/metrics.html");
        HttpResponse<String> metricsJs = get("/metrics.js");
        HttpResponse<String> metricsCss = get("/metrics.css");

        assertEquals(200, js.statusCode());
        assertEquals(200, css.statusCode());
        assertEquals(200, chart.statusCode());
        assertEquals(200, metricsHtml.statusCode());
        assertEquals(200, metricsJs.statusCode());
        assertEquals(200, metricsCss.statusCode());
        assertTrue(js.headers().firstValue("content-type").orElse("").contains("text/javascript"));
        assertTrue(css.headers().firstValue("content-type").orElse("").contains("text/css"));
        assertTrue(chart.headers().firstValue("content-type").orElse("").contains("text/javascript"));
        assertTrue(metricsHtml.headers().firstValue("content-type").orElse("").contains("text/html"));
        assertTrue(metricsJs.headers().firstValue("content-type").orElse("").contains("text/javascript"));
        assertTrue(metricsCss.headers().firstValue("content-type").orElse("").contains("text/css"));
        assertTrue(js.body().contains("/quotes/updates?symbols="));
        assertTrue(metricsHtml.body().contains("Kalshi Ops Metrics"));
        assertTrue(metricsHtml.body().contains("Hot-path p99"));
        assertTrue(metricsHtml.body().contains("Read-Model Freshness"));
        assertTrue(metricsHtml.body().contains("metrics.js"));
        assertTrue(metricsJs.body().contains("/ops/pipeline"));
        assertTrue(metricsJs.body().contains("/ops/latency"));
        assertTrue(metricsJs.body().contains("/ops/hot-path-latency"));
        assertTrue(metricsJs.body().contains("/metrics?format=prometheus"));
        assertTrue(metricsJs.body().contains("excludes DB/read-model"));
        assertTrue(metricsCss.body().contains(".metric-grid"));
        assertTrue(js.body().contains("const MARKET_CATALOG_LIMIT = 200;"));
        assertTrue(js.body().contains("marketCatalogGeneration"));
        assertTrue(js.body().contains("marketCatalogAbortController"));
        assertTrue(js.body().contains("ensureActiveMarketCatalogRequest"));
        assertTrue(js.body().contains("isStaleMarketCatalogError"));
        assertTrue(js.body().contains("/api/markets/capabilities?"));
        assertTrue(js.body().contains("market-capability-filter"));
        assertTrue(js.body().contains("market-page-state"));
        assertTrue(js.body().contains("'/markets?' + params.join('&')"));
        assertTrue(js.body().contains("market-search"));
        assertTrue(js.body().contains("market-status-filter"));
        assertTrue(js.body().contains("market-search-apply"));
        assertTrue(js.body().contains("market-state"));
        assertTrue(js.body().contains("markets.markets.length > 0"));
        assertTrue(js.body().contains("/features?"));
        assertTrue(js.body().contains("/health"));
        assertTrue(js.body().contains("document.getElementById('release-identity')"));
        assertTrue(js.body().contains("document.getElementById('health-data-age')"));
        assertTrue(js.body().contains("document.getElementById('quote-update-health')"));
        assertTrue(js.body().contains("document.getElementById('product-readiness-state')"));
        assertTrue(js.body().contains("document.getElementById('runtime-feature-source')"));
        assertTrue(js.body().contains("document.getElementById('freshness-age-ms')"));
        assertTrue(js.body().contains("document.getElementById('operator-env-plan')"));
        assertTrue(js.body().contains("document.getElementById('demo-run-action')"));
        assertTrue(js.body().contains("document.getElementById('demo-run-start')"));
        assertTrue(js.body().contains("document.getElementById('demo-run-live-credentials')"));
        assertTrue(js.body().contains("document.getElementById('distribution-sample-payload')"));
        assertTrue(js.body().contains("document.getElementById('replay-status-state')"));
        assertTrue(js.body().contains("document.getElementById('latency-throughput')"));
        assertTrue(js.body().contains("document.getElementById('demo-signal-throughput')"));
        assertTrue(js.body().contains("CHART_AUTO_REFRESH_MS"));
        assertTrue(js.body().contains("demoActionRequiresConfirm"));
        assertTrue(js.body().contains("live_catalog_sync_bounded"));
        assertTrue(js.body().contains("s3PreflightText"));
        assertTrue(js.body().contains("document.getElementById('operator-control-enabled')"));
        assertTrue(js.body().contains("document.getElementById('operator-command-plan')"));
        assertTrue(js.body().contains("document.getElementById('semantic-run-start')"));
        assertTrue(js.body().contains("document.getElementById('semantic-run-openrouter-key')"));
        assertTrue(js.body().contains("document.getElementById('catalog-sync-start')"));
        assertTrue(js.body().contains("document.getElementById('catalog-sync-series')"));
        assertTrue(js.body().contains("document.getElementById('catalog-sync-mve-filter')"));
        assertTrue(js.body().contains("document.getElementById('trader-bid')"));
        assertTrue(js.body().contains("document.getElementById('research-feature-limit')"));
        assertTrue(js.body().contains("document.getElementById('semantic-group-by')"));
        assertTrue(js.body().contains("document.getElementById('semantic-treemap')"));
        assertTrue(js.body().contains("document.getElementById('operator-e2e-latency')"));
        assertTrue(js.body().contains("body.release"));
        assertTrue(js.body().contains("body.data_freshness"));
        assertTrue(js.body().contains("body.quote_streams"));
        assertTrue(js.body().contains("/operator/status"));
        assertTrue(js.body().contains("/operator/plan"));
        assertTrue(js.body().contains("/operator/catalog/sync"));
        assertTrue(js.body().contains("/operator/catalog/sync-status"));
        assertTrue(js.body().contains("/operator/semantic-metadata/run"));
        assertTrue(js.body().contains("/operator/semantic-metadata/run-status"));
        assertTrue(js.body().contains("/operator/demo-orchestrator/run"));
        assertTrue(js.body().contains("/operator/demo-orchestrator/run-status"));
        assertTrue(js.body().contains("/api/demo/replay/status"));
        assertTrue(js.body().contains("/ops/pipeline"));
        assertTrue(js.body().contains("/ops/latency"));
        assertTrue(js.body().contains("ms projection"));
        assertTrue(js.body().contains("/api/semantic-metadata/treemap?"));
        assertTrue(js.body().contains("/api/semantic-metadata/markets?"));
        assertTrue(js.body().contains("SEMANTIC_MAP_DEFAULT_LIMIT"));
        assertTrue(js.body().contains("requestSemanticMapLoad"));
        assertTrue(js.body().contains("semanticMapDirty"));
        assertTrue(js.body().contains("marketEntries.some(entry => entry.symbol === marketTicker)"));
        assertFalse(js.body().contains("ensureSymbolOption"));
        assertFalse(js.body().contains("loadSemanticMap();\n        loadCatalogSyncStatus();"));
        assertTrue(js.body().contains("layoutSemanticLeafTreemap"));
        assertTrue(js.body().contains("semanticRenderableLeaves"));
        assertTrue(js.body().contains("SEMANTIC_RENDER_LEAF_LIMIT"));
        assertTrue(js.body().contains("Eligible generated"));
        assertTrue(js.body().contains("semanticStatusFromCatalogStatus"));
        assertTrue(js.body().contains("nonChartableMessage"));
        assertTrue(js.body().contains("body.product_readiness"));
        assertTrue(js.body().contains("generateOperatorPlan"));
        assertTrue(js.body().contains("buildCatalogSyncRequest"));
        assertTrue(js.body().contains("buildDemoRunRequest"));
        assertTrue(js.body().contains("renderDemoOrchestratorStatus"));
        assertTrue(js.body().contains("applyRoleVisibility"));
        assertTrue(js.body().contains("renderDistributionStatus"));
        assertTrue(js.body().contains("renderDemoSignal"));
        assertTrue(js.body().contains("loadReplayStatus"));
        assertTrue(js.body().contains("dataFreshnessBadgeText"));
        assertTrue(js.body().contains("startCatalogSync"));
        assertTrue(js.body().contains("loadCatalogSyncStatus"));
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
        assertTrue(css.body().contains("chart-state"));
        assertTrue(css.body().contains("market-pagination"));
        assertTrue(css.body().contains("semantic-treemap"));
        assertTrue(css.body().contains("role-hidden"));
        assertTrue(css.body().contains("demo-orchestrator-grid"));
        assertTrue(css.body().contains("data-active-role=\"distribution\""));
        assertTrue(css.body().contains("data-active-role=\"replay\""));
        assertTrue(css.body().contains("ticker-text"));
        assertTrue(css.body().contains("source-event-text"));
        assertTrue(css.body().contains("text-overflow: ellipsis"));
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
    void featuresEndpointSupportsEventTimeWindow() throws Exception {
        JsonNode body = getJson("/features?symbol=MKT-1&feature=feature.bbo&limit=10&from_ms=4500&to_ms=4500");

        assertEquals(1, body.path("count").asInt());
        assertEquals(4_500L, body.path("outputs").get(0).path("event_ts_ms").asLong());
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
        assertTrue(body.path("data_freshness").path("latest_event_ts_ms").asLong() > 5_000L);
        assertEquals("MKT-ELIGIBLE", body.path("data_freshness").path("symbol").asText());
        assertEquals("feature.bbo", body.path("data_freshness").path("feature_name").asText());
        assertEquals("recent-evt-9", body.path("data_freshness").path("source_event_id").asText());
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
        assertEquals("disabled", body.path("semantic_metadata").path("status").asText());
        assertFalse(body.path("semantic_metadata").path("runtime_supported").asBoolean());
        assertEquals("disabled", body.path("replay_demo").path("status").asText());
        assertEquals("demo-db-primary-long-replay", body.path("replay_demo").path("replay_id").asText());
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
    void replayDemoStatusEndpointReportsPopulatedDataset() throws Exception {
        restartWithReplayDemoStatus(ReplayDemoStatus.fromCounts(
            "demo-db-primary-long-replay",
            2L,
            366L,
            362L,
            0L,
            1_800_000_000_000L,
            1_800_000_180_000L,
            10L,
            375L,
            List.of("DEMO-DBPRIMARY-26MAY19-T50", "DEMO-DBPRIMARY-26MAY19-T60")
        ));

        JsonNode body = getJson("/api/demo/replay/status");

        assertEquals("projected", body.path("status").asText());
        JsonNode replay = body.path("replay_demo");
        assertEquals("demo-db-primary-long-replay", replay.path("replay_id").asText());
        assertEquals(2L, replay.path("market_count").asLong());
        assertEquals(366L, replay.path("canonical_event_count").asLong());
        assertEquals(362L, replay.path("feature_output_count").asLong());
        assertEquals(0L, replay.path("latest_market_state_count").asLong());
        assertEquals(2, replay.path("available_symbols").size());
        assertTrue(replay.path("featureplant_projected").asBoolean());
        assertTrue(replay.path("dataset_ready").asBoolean());
        assertEquals("projected", getJson("/health").path("replay_demo").path("status").asText());
        assertEquals("projected", getJson("/operator/status").path("replay_demo").path("status").asText());
    }

    @Test
    void replayDemoStatusEndpointHandlesEmptyDataset() throws Exception {
        restartWithReplayDemoStatus(ReplayDemoStatus.fromCounts(
            "demo-db-primary-long-replay",
            0L,
            0L,
            0L,
            0L,
            null,
            null,
            null,
            null,
            List.of()
        ));

        JsonNode replay = getJson("/api/demo/replay/status").path("replay_demo");

        assertEquals("empty", replay.path("status").asText());
        assertEquals(0L, replay.path("canonical_event_count").asLong());
        assertFalse(replay.path("featureplant_projected").asBoolean());
        assertFalse(replay.path("dataset_ready").asBoolean());
        assertEquals(0, replay.path("available_symbols").size());
    }

    @Test
    void opsPipelineAndLatencyExposeStructuredTelemetry() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0"));
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(feature("MKT-1", System.currentTimeMillis() - 100L, "evt-5000", 500_000L));
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            () -> new OperatorPipelineStatus("ok", "featureplant-prod", 12L, 12L, 0L, 12L, 25L,
                5L, 4L, 3L, 900L, null),
            sourceEventId -> new OperatorLatencyStatus("ok", sourceEventId, "MKT-1", 12L, 12L,
                5L, 7L, 12L, null, null),
            FrontendReleaseInfo.empty()
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode pipeline = getJson("/ops/pipeline");

        assertEquals("ok", pipeline.path("status").asText());
        JsonNode pipelineBody = pipeline.path("pipeline");
        assertEquals("featureplant-prod", pipelineBody.path("cursor_name").asText());
        assertEquals(12L, pipelineBody.path("canonical_max_commit_seq").asLong());
        assertEquals(0L, pipelineBody.path("cursor_lag_events").asLong());
        assertEquals(5L, pipelineBody.path("recent_canonical_events").asLong());
        assertEquals(4L, pipelineBody.path("recent_feature_outputs").asLong());
        assertEquals(3L, pipelineBody.path("recent_latest_market_states").asLong());
        assertEquals(900L, pipelineBody.path("recent_window_seconds").asLong());

        JsonNode latency = getJson("/ops/latency?source_event_id=evt-5000");

        assertEquals("ok", latency.path("status").asText());
        assertEquals("evt-5000", latency.path("source_event_id").asText());
        assertEquals(12L, latency.path("canonical_commit_seq").asLong());
        assertEquals(12L, latency.path("latest_market_state_commit_seq").asLong());
        assertEquals(5L, latency.path("canonical_to_feature_ms").asLong());
        assertEquals(7L, latency.path("feature_to_latest_state_ms").asLong());
        assertEquals(12L, latency.path("canonical_to_latest_state_ms").asLong());
    }

    @Test
    void opsHotPathLatencyExposesRealHotPathDistributionTelemetry() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0"));
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.setHotPathLatencyStatusSupplier(() -> new HotPathLatencyStatus(
            "ok",
            "prometheus",
            "Hot-path latency excludes DB read-model projection.",
            List.of(new HotPathLatencyStatus.Stage(
                "featureplant_bbo_module_processing",
                "FeaturePlant BBO module processing",
                "ok",
                "featureplant",
                "feature_module_latency_ns",
                "module-only",
                List.of(new HotPathLatencyStatus.Series(
                    Map.of("service", "featureplant", "module", "feature.bbo", "stream", "derived.top_of_book"),
                    64L,
                    2L,
                    4_000L,
                    7_000L,
                    9_000L,
                    20_000L,
                    5_000L
                ))
            )),
            null
        ));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode hotPath = getJson("/ops/hot-path-latency");

        assertEquals("ok", hotPath.path("status").asText());
        assertEquals("prometheus", hotPath.path("source").asText());
        assertTrue(hotPath.path("note").asText().contains("excludes"));
        JsonNode stage = hotPath.path("stages").get(0);
        assertEquals("featureplant_bbo_module_processing", stage.path("id").asText());
        assertEquals("feature_module_latency_ns", stage.path("metric").asText());
        assertEquals(9_000L, stage.path("series").get(0).path("p99_ns").asLong());
        assertFalse(hotPath.toString().contains("latest_market_state_commit_seq"));
        assertFalse(hotPath.toString().contains("canonical_to_latest_state_ms"));
    }

    @Test
    void opsLatencyReturnsMissingWhenNoSourceEventIsAvailable() throws Exception {
        server.stop();
        server = new FrontendAdapterServer(
            FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0")),
            new FrontendFeatureStore(100, 100),
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode latency = getJson("/ops/latency");

        assertEquals("missing", latency.path("status").asText());
        assertEquals("missing_source_event_id", latency.path("reason").asText());
        assertTrue(latency.path("canonical_commit_seq").isNull());
    }

    @Test
    void healthAndOperatorStatusBoundSlowOptionalStatusSuppliers() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0"));
        Supplier<OperatorPipelineStatus> slowPipeline = () -> {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));
            return new OperatorPipelineStatus("ok", "cursor", 1L, 1L, 0L, 1L, 1L, null);
        };
        Supplier<OperatorSemanticMetadataStatus> slowSemantic = () -> {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));
            return OperatorSemanticMetadataStatus.disabled("m", "f", "tax");
        };
        Supplier<ReplayDemoStatus> slowReplay = () -> {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));
            return ReplayDemoStatus.disabled("demo");
        };
        server = new FrontendAdapterServer(
            config,
            new FrontendFeatureStore(100, 100),
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            slowPipeline,
            sourceEventId -> OperatorLatencyStatus.disabled(),
            slowSemantic,
            request -> List.of(),
            slowReplay,
            FrontendReleaseInfo.empty()
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        long startNs = System.nanoTime();
        JsonNode health = getJson("/health");
        JsonNode operator = getJson("/operator/status");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertTrue(elapsedMs < 2_500L, "optional status calls should be bounded, elapsedMs=" + elapsedMs);
        assertEquals("unavailable", health.path("operator_pipeline").path("status").asText());
        assertEquals("status_timeout", health.path("operator_pipeline").path("error").asText());
        assertEquals("unavailable", health.path("semantic_metadata").path("status").asText());
        assertEquals("status_timeout", health.path("semantic_metadata").path("error").asText());
        assertEquals("unavailable", health.path("replay_demo").path("status").asText());
        assertEquals("status_timeout", health.path("replay_demo").path("error").asText());
        assertEquals("unavailable", operator.path("pipeline").path("status").asText());
        assertEquals("unavailable", operator.path("semantic_metadata").path("status").asText());
        assertEquals("unavailable", operator.path("replay_demo").path("status").asText());
    }

    @Test
    void opsLatencyUsesLatestStateFallbackWhenDefaultLatencyReadTimesOut() throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(feature("MKT-1", System.currentTimeMillis() - 50L, "evt-slow-latency", 500_000L));
        server = new FrontendAdapterServer(
            FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0")),
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            OperatorPipelineStatus::disabled,
            sourceEventId -> {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));
                return new OperatorLatencyStatus("ok", sourceEventId, "MKT-1", 1L, 1L, 1L, 1L, 2L, null, null);
            },
            FrontendReleaseInfo.empty()
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        long startNs = System.nanoTime();
        JsonNode latency = getJson("/ops/latency");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertTrue(elapsedMs < 1_000L, "latency endpoint should be bounded, elapsedMs=" + elapsedMs);
        assertEquals("missing", latency.path("status").asText());
        assertEquals("latency_reader_timeout", latency.path("reason").asText());
        assertEquals("latest_state_freshness", latency.path("fallback_source").asText());
        assertEquals("evt-slow-latency", latency.path("latest_state_source_event_id").asText());
        assertTrue(latency.path("latest_state_age_ms").asLong() >= 0L);
        assertEquals(1L, latency.path("store_sequence").asLong());
    }

    @Test
    void healthAndOperatorStatusExposeSemanticMetadataStatus() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://db/kalshi",
            "LLM_METADATA_MODEL", "deepseek/deepseek-v4-flash:free",
            "LLM_METADATA_FALLBACK_MODEL", "deepseek/deepseek-v4-flash",
            "LLM_METADATA_TAXONOMY_VERSION", "tax-v1"
        ));
        server = new FrontendAdapterServer(
            config,
            new FrontendFeatureStore(100, 100),
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            OperatorPipelineStatus::disabled,
            sourceEventId -> OperatorLatencyStatus.disabled(),
            () -> new OperatorSemanticMetadataStatus(
                "ok",
                true,
                "deepseek/deepseek-v4-flash:free",
                "deepseek/deepseek-v4-flash",
                "tax-v1",
                7L,
                1L,
                2L,
                3L,
                Instant.parse("2026-05-20T00:00:00Z"),
                25L,
                null
            ),
            FrontendReleaseInfo.empty()
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode health = getJson("/health").path("semantic_metadata");
        JsonNode status = getJson("/operator/status");
        JsonNode operatorSemantic = status.path("semantic_metadata");
        JsonNode configSemantic = status.path("configuration").path("semantic_metadata");

        assertEquals("ok", health.path("status").asText());
        assertEquals(7L, health.path("generated_count").asLong());
        assertEquals(3L, health.path("rate_limited_count").asLong());
        assertEquals("offline_batch_only", health.path("runtime_status").asText());
        assertFalse(health.path("execution_enabled").asBoolean());
        assertTrue(health.path("read_api").path("available").asBoolean());
        assertEquals(13L, health.path("read_api").path("row_count").asLong());
        assertEquals("tax-v1", operatorSemantic.path("taxonomy_version").asText());
        assertEquals("offline_batch", configSemantic.path("execution_mode").asText());
        assertTrue(configSemantic.path("read_api_enabled").asBoolean());
        assertFalse(configSemantic.path("runtime_execution_enabled").asBoolean());
        assertFalse(status.toString().contains("OPENROUTER_API_KEY"));
    }

    @Test
    void semanticMarketsEndpointReturnsRowsAndHidesRawResponse() throws Exception {
        FakeSemanticMarketMetadataReader reader = restartWithSemanticReader(List.of(semanticRow("MKT-SEM", "weather")));

        HttpResponse<String> response = get(
            "/api/semantic-metadata/markets?limit=999&status=generated&tag=forecast&q=rain"
        );
        JsonNode body = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertEquals("ok", body.path("status").asText());
        assertEquals("tax-v1", body.path("taxonomy_version").asText());
        assertEquals(500, body.path("limit").asInt());
        assertEquals(1, body.path("count").asInt());
        assertEquals("MKT-SEM", body.path("markets").get(0).path("market_ticker").asText());
        assertEquals("Will it rain?", body.path("markets").get(0).path("title").asText());
        JsonNode metadata = body.path("markets").get(0).path("semantic_metadata");
        assertEquals("weather", metadata.path("sector").asText());
        assertEquals("forecast", metadata.path("event_type").asText());
        assertEquals("forecast", metadata.path("tags").get(0).asText());
        assertEquals(450_000L, body.path("markets").get(0).path("quote").path("midpoint_micros").asLong());
        assertFalse(response.body().contains("raw_response"));

        SemanticMarketMetadataReadRequest request = reader.requests.get(0);
        assertEquals("tax-v1", request.taxonomyVersion());
        assertEquals("generated", request.semanticStatus());
        assertEquals("forecast", request.tag());
        assertEquals("rain", request.query());
        assertEquals(500, request.maxRows());
    }

    @Test
    void semanticTreemapEndpointGroupsRowsBySector() throws Exception {
        restartWithSemanticReader(List.of(
            semanticRow("MKT-SEM", "weather"),
            semanticRow("MKT-POL", "politics")
        ));

        JsonNode body = getJson("/api/semantic-metadata/treemap?group_by=sector&limit=10");

        assertEquals("ok", body.path("status").asText());
        assertEquals("sector", body.path("group_by").asText());
        assertEquals(2, body.path("count").asInt());
        assertEquals(2, body.path("groups").size());
        JsonNode weather = group(body.path("groups"), "weather");
        assertEquals(123L, weather.path("value").asLong());
        assertEquals(1, weather.path("count").asInt());
        assertEquals("MKT-SEM", weather.path("leaves").get(0).path("market_ticker").asText());
        assertEquals(123L, weather.path("leaves").get(0).path("quote").path("open_interest").asLong());
        assertEquals("forecast", weather.path("leaves").get(0).path("tags").get(0).asText());
    }

    @Test
    void semanticTreemapEndpointAggregatesManyGroupsAndTags() throws Exception {
        restartWithSemanticReader(List.of(
            semanticRow("MKT-WX-RAIN", "weather", "forecast", List.of("forecast", "rain"), 100L),
            semanticRow("MKT-WX-CLIMATE", "weather", "climate", List.of("climate", "rain"), 200L),
            semanticRow("MKT-FIN-RATES", "finance", "macro", List.of("macro", "rates"), 300L)
        ));

        JsonNode sectorBody = getJson("/api/semantic-metadata/treemap?group_by=sector&limit=10");
        JsonNode tagBody = getJson("/api/semantic-metadata/treemap?group_by=tag&limit=10");

        assertEquals("ok", sectorBody.path("status").asText());
        assertEquals(3, sectorBody.path("count").asInt());
        JsonNode weather = group(sectorBody.path("groups"), "weather");
        assertEquals(2, weather.path("count").asInt());
        assertEquals(300L, weather.path("value").asLong());
        JsonNode finance = group(sectorBody.path("groups"), "finance");
        assertEquals(1, finance.path("count").asInt());
        assertEquals(300L, finance.path("value").asLong());

        assertEquals("tag", tagBody.path("group_by").asText());
        assertEquals(3, tagBody.path("count").asInt());
        JsonNode rain = group(tagBody.path("groups"), "rain");
        assertEquals(2, rain.path("count").asInt());
        assertEquals(300L, rain.path("value").asLong());
        assertEquals(1, group(tagBody.path("groups"), "forecast").path("count").asInt());
        assertEquals(1, group(tagBody.path("groups"), "rates").path("count").asInt());
    }

    @Test
    void semanticTreemapEndpointNormalizesGroupKeysAndLabels() throws Exception {
        restartWithSemanticReader(List.of(
            semanticRow("MKT-SPORTS-A", "Sports Betting", "Live Event", List.of("Hot Market", "hot market"), 100L),
            semanticRow("MKT-SPORTS-B", "sports betting", "live event", List.of("HOT MARKET"), 200L)
        ));

        JsonNode sectorBody = getJson("/api/semantic-metadata/treemap?group_by=sector&limit=10");
        JsonNode tagBody = getJson("/api/semantic-metadata/treemap?group_by=tag&limit=10");

        JsonNode sector = group(sectorBody.path("groups"), "sports betting");
        assertEquals("Sports Betting", sector.path("label").asText());
        assertEquals(2, sector.path("count").asInt());
        assertEquals(300L, sector.path("value").asLong());
        JsonNode tag = group(tagBody.path("groups"), "hot market");
        assertEquals("Hot Market", tag.path("label").asText());
        assertEquals(2, tag.path("count").asInt());
    }

    @Test
    void semanticEndpointsReturnDisabledWhenSourceDisabled() throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://db/kalshi",
            "FRONTEND_ADAPTER_SEMANTIC_METADATA_STATUS_SOURCE", "disabled",
            "LLM_METADATA_TAXONOMY_VERSION", "tax-v1"
        ));
        server = semanticServer(config, request -> {
            throw new IllegalStateException("reader should not run");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();

        JsonNode markets = getJson("/api/semantic-metadata/markets");
        JsonNode treemap = getJson("/api/semantic-metadata/treemap");

        assertEquals("disabled", markets.path("status").asText());
        assertEquals(0, markets.path("count").asInt());
        assertTrue(markets.path("markets").isArray());
        assertEquals(0, markets.path("markets").size());
        assertEquals("disabled", treemap.path("status").asText());
        assertEquals(0, treemap.path("groups").size());
    }

    @Test
    void semanticEndpointsReturnUnavailableWithRedactedErrors() throws Exception {
        String sensitive = "password=hunter2 Authorization: Basic secret-token";
        restartWithSemanticReader(request -> {
            throw new IllegalStateException(sensitive);
        });

        HttpResponse<String> response = get("/api/semantic-metadata/markets");
        JsonNode body = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertEquals("unavailable", body.path("status").asText());
        assertEquals(0, body.path("count").asInt());
        assertFalse(response.body().contains("hunter2"));
        assertFalse(response.body().contains("secret-token"));
        assertTrue(response.body().contains("[redacted]"));
    }

    @Test
    void semanticEndpointsUseBasicAuth() throws Exception {
        restartWithBasicAuth();

        assertEquals(401, get("/api/semantic-metadata/markets").statusCode());
        assertEquals(200, getWithBasicAuth("/api/semantic-metadata/markets", "operator", "secret").statusCode());
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
        assertTrue(checkPassed(body, "semantic_runtime_execution_disabled"));
    }

    @Test
    void operatorPlanBlocksSemanticRuntimeExecution() throws Exception {
        restartWithOperatorControl(true);
        String request = """
            {
              "profile": "live-product",
              "kalshi": {"key_id": "key-123", "private_key_path": "/run/secrets/kalshi_private_key"},
              "db": {"url": "jdbc:postgresql://db/kalshi", "user": "frontend", "password_present": true},
              "basic_auth": {"user": "operator", "password_present": true},
              "semantic_metadata": {"source": "db", "corpus_present": true, "runtime_execution_requested": true},
              "release": {"ref": "abcdef123456"}
            }
            """;

        JsonNode body = MAPPER.readTree(postJsonWithBasicAuth(
            "/operator/plan",
            request,
            "operator",
            "secret"
        ).body());

        assertFalse(body.path("can_deploy").asBoolean());
        assertFalse(checkPassed(body, "semantic_runtime_execution_disabled"));
        assertTrue(checkPassed(body, "semantic_metadata_source"));
        assertTrue(checkPassed(body, "semantic_metadata_corpus"));
        assertEquals("fix blocked checklist items before compose up",
            body.path("commands").get(1).asText());
    }

    @Test
    void operatorPlanDoesNotRenderCommandsForUnsupportedProfiles() throws Exception {
        restartWithOperatorControl(true);
        String request = "{\"profile\":\"live-product; touch /tmp/pwn\"}";

        JsonNode body = MAPPER.readTree(postJsonWithBasicAuth(
            "/operator/plan",
            request,
            "operator",
            "secret"
        ).body());

        assertEquals("blocked", body.path("status").asText());
        assertFalse(checkPassed(body, "profile_supported"));
        String commandPlan = body.path("commands").toString();
        assertFalse(commandPlan.contains("touch"));
        assertFalse(commandPlan.contains("docker compose"));
        assertTrue(commandPlan.contains("supported profile"));
    }

    @Test
    void operatorPlanIsProtectedByBasicAuthWhenConfigured() throws Exception {
        restartWithOperatorControl(true);
        String request = "{\"profile\":\"long-replay-demo\"}";

        assertEquals(401, postJson("/operator/plan", request).statusCode());
        assertEquals(200, postJsonWithBasicAuth("/operator/plan", request, "operator", "secret").statusCode());
    }

    @Test
    void operatorSemanticMetadataRunPostIsDisabledByDefault() throws Exception {
        HttpResponse<String> response = postJson("/operator/semantic-metadata/run", "{\"dry_run\":true}");

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("operator control POST is disabled"));
    }

    @Test
    void operatorSemanticMetadataRunEnabledStillRequiresBasicAuth() throws Exception {
        restartWithOperatorControl(false);

        HttpResponse<String> response = postJson("/operator/semantic-metadata/run", "{\"dry_run\":true}");

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("requires Basic Auth"));
    }

    @Test
    void operatorSemanticMetadataRunStartsAsyncAndBlocksConcurrentRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<SemanticMetadataConfig> observed = new AtomicReference<>();
        String requestKey = "sk-test-run-secret-000000000000000000000000";
        restartWithSemanticOperator(
            Map.of(),
            config -> {
                observed.set(config);
                started.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return summary(2, 1, 0, 0, 0, 1);
            }
        );

        String request = """
            {
              "dry_run": false,
              "overwrite": true,
              "max_markets": 2,
              "max_tokens": 2200,
              "max_retries": 3,
              "market_ticker": "MKT-SEM",
              "model": "deepseek/deepseek-v4-flash:free",
              "fallback_model": "deepseek/deepseek-v4-flash",
              "allow_paid_fallback": false,
              "openrouter_api_key": "%s"
            }
            """.formatted(requestKey);
        HttpResponse<String> response =
            postJsonWithBasicAuth("/operator/semantic-metadata/run", request, "operator", "secret");
        JsonNode body = MAPPER.readTree(response.body());

        assertEquals(202, response.statusCode());
        assertEquals("running", body.path("status").asText());
        assertTrue(body.path("running").asBoolean());
        assertFalse(response.body().contains(requestKey));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals("MKT-SEM", observed.get().marketTicker());
        assertTrue(observed.get().overwrite());
        assertEquals(2200, observed.get().maxTokens());
        assertEquals(3, observed.get().maxRetries());
        assertEquals(observed.get().model(), observed.get().fallbackModel());
        assertEquals(409, postJsonWithBasicAuth(
            "/operator/semantic-metadata/run",
            request,
            "operator",
            "secret"
        ).statusCode());

        release.countDown();
        JsonNode completed = waitForSemanticRunState("completed");
        assertEquals(1, completed.path("latest_run").path("summary").path("generated").asInt());
        assertEquals(2200, completed.path("latest_run").path("config").path("max_tokens").asInt());
        assertEquals(3, completed.path("latest_run").path("config").path("max_retries").asInt());
        assertTrue(completed.path("latest_run").path("config").path("overwrite").asBoolean());
        assertFalse(completed.toString().contains(requestKey));
    }

    @Test
    void operatorSemanticMetadataRunDryRunDoesNotRequireApiKey() throws Exception {
        AtomicReference<SemanticMetadataConfig> observed = new AtomicReference<>();
        restartWithSemanticOperator(
            Map.of("OPENROUTER_API_KEY", ""),
            config -> {
                observed.set(config);
                return summary(1, 0, 0, 0, 0, 1);
            }
        );

        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/semantic-metadata/run",
            "{\"dry_run\":true,\"max_markets\":1}",
            "operator",
            "secret"
        );

        assertEquals(202, response.statusCode());
        JsonNode completed = waitForSemanticRunState("completed");
        assertTrue(observed.get().dryRun());
        assertEquals("", observed.get().openRouterApiKey());
        assertEquals(1, completed.path("latest_run").path("summary").path("skipped").asInt());
    }

    @Test
    void operatorSemanticMetadataRunRejectsMissingApiKeyForNonDryRun() throws Exception {
        restartWithSemanticOperator(Map.of("OPENROUTER_API_KEY", ""), config -> summary(0, 0, 0, 0, 0, 0));

        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/semantic-metadata/run",
            "{\"dry_run\":false,\"max_markets\":1}",
            "operator",
            "secret"
        );

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("OPENROUTER_API_KEY"));
    }

    @Test
    void operatorSemanticMetadataRunStatusRedactsRuntimeError() throws Exception {
        String requestKey = "sk-test-error-secret-0000000000000000000000";
        restartWithSemanticOperator(
            Map.of(),
            config -> {
                throw new IllegalStateException("provider failed api_key=" + config.openRouterApiKey());
            }
        );

        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/semantic-metadata/run",
            "{\"dry_run\":false,\"openrouter_api_key\":\"" + requestKey + "\"}",
            "operator",
            "secret"
        );

        assertEquals(202, response.statusCode());
        JsonNode failed = waitForSemanticRunState("failed");
        String raw = failed.toString();
        assertFalse(raw.contains(requestKey));
        assertTrue(failed.path("latest_run").path("last_error").asText().contains("[redacted]"));
    }

    @Test
    void operatorCatalogSyncPostIsDisabledByDefault() throws Exception {
        HttpResponse<String> response = postJson("/operator/catalog/sync", "{\"dry_run\":true}");

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("operator control POST is disabled"));
    }

    @Test
    void operatorCatalogSyncEnabledStillRequiresBasicAuth() throws Exception {
        restartWithOperatorControl(false);

        HttpResponse<String> response = postJson("/operator/catalog/sync", "{\"dry_run\":true}");

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("requires Basic Auth"));
    }

    @Test
    void operatorCatalogSyncStartsAsyncAndBlocksConcurrentRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<CatalogSyncOperatorService.CatalogSyncRunConfig> observed = new AtomicReference<>();
        String keyId = "catalog-test-key-id";
        String keyPath = "/run/secrets/catalog-test-key.pem";
        restartWithCatalogSyncOperator(
            Map.of("KALSHI_KEY_ID", keyId, "KALSHI_KEY_PATH", keyPath),
            config -> {
                observed.set(config);
                started.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return catalogSummary(2, 3, 2, 2, 0);
            }
        );

        String request = """
            {
              "dry_run": false,
              "series_ticker": "KXDEMO",
              "market_status": "open",
              "limit": 25,
              "max_pages": 2,
              "max_tickers": 2,
              "mve_filter": "exclude"
            }
            """;
        HttpResponse<String> response =
            postJsonWithBasicAuth("/operator/catalog/sync", request, "operator", "secret");
        JsonNode body = MAPPER.readTree(response.body());

        assertEquals(202, response.statusCode());
        assertEquals("running", body.path("status").asText());
        assertTrue(body.path("running").asBoolean());
        assertFalse(response.body().contains(keyId));
        assertFalse(response.body().contains(keyPath));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        CatalogSyncOperatorService.CatalogSyncRunConfig runConfig = observed.get();
        assertEquals("KXDEMO", runConfig.request().seriesTicker());
        assertEquals("open", runConfig.request().marketStatus());
        assertEquals(25, runConfig.request().limit());
        assertEquals(2, runConfig.request().maxPages());
        assertEquals(2, runConfig.request().maxTickers());
        assertEquals("exclude", runConfig.request().mveFilter());
        assertEquals(409, postJsonWithBasicAuth("/operator/catalog/sync", request, "operator", "secret").statusCode());

        release.countDown();
        JsonNode completed = waitForCatalogSyncRunState("completed");
        assertEquals(2, completed.path("latest_run").path("summary").path("rows_upserted").asInt());
        assertFalse(completed.toString().contains(keyId));
        assertFalse(completed.toString().contains(keyPath));
    }

    @Test
    void operatorCatalogSyncStatusReportsReadinessWithoutSecrets() throws Exception {
        String keyId = "catalog-readiness-key";
        String keyPath = "/run/secrets/catalog-readiness.pem";
        restartWithCatalogSyncOperator(
            Map.of("KALSHI_KEY_ID", keyId, "KALSHI_KEY_PATH", keyPath),
            config -> catalogSummary(0, 0, 0, 0, 0)
        );

        JsonNode status = MAPPER.readTree(getWithBasicAuth(
            "/operator/catalog/sync-status",
            "operator",
            "secret"
        ).body());
        JsonNode health = MAPPER.readTree(getWithBasicAuth("/health", "operator", "secret").body());
        JsonNode operator = MAPPER.readTree(getWithBasicAuth("/operator/status", "operator", "secret").body());

        assertTrue(status.path("db_configured").asBoolean());
        assertTrue(status.path("kalshi_key_id_configured").asBoolean());
        assertTrue(status.path("kalshi_private_key_path_configured").asBoolean());
        assertTrue(health.path("catalog_sync").path("kalshi_key_id_configured").asBoolean());
        assertEquals(
            "operator_async_catalog_only",
            operator.path("configuration").path("catalog_sync").path("execution_mode").asText()
        );
        assertTrue(operator.path("configuration").path("catalog_sync").path("db_configured").asBoolean());
        assertTrue(operator.path("catalog_sync_run").path("kalshi_key_id_configured").asBoolean());
        String raw = operator.toString() + status.toString() + health.toString();
        assertFalse(raw.contains(keyId));
        assertFalse(raw.contains(keyPath));
    }

    @Test
    void operatorCatalogSyncAllowsPublicCatalogWithoutKalshiCredentials() throws Exception {
        restartWithCatalogSyncOperator(
            Map.of("KALSHI_KEY_ID", "", "KALSHI_KEY_PATH", ""),
            config -> catalogSummary(0, 0, 0, 0, 0)
        );

        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/catalog/sync",
            "{\"dry_run\":true,\"max_pages\":1}",
            "operator",
            "secret"
        );

        assertEquals(202, response.statusCode());
        JsonNode completed = waitForCatalogSyncRunState("completed");
        assertFalse(completed.path("kalshi_key_id_configured").asBoolean());
        assertFalse(completed.path("kalshi_private_key_path_configured").asBoolean());
    }

    @Test
    void operatorCatalogSyncStatusRedactsRuntimeError() throws Exception {
        String keyId = "catalog-error-key-id";
        String keyPath = "/run/secrets/catalog-error-key.pem";
        restartWithCatalogSyncOperator(
            Map.of("KALSHI_KEY_ID", keyId, "KALSHI_KEY_PATH", keyPath),
            config -> {
                throw new IllegalStateException(
                    "catalog failed key=" + config.kalshiKeyId()
                        + " path=" + config.kalshiKeyPath()
                        + " password=" + config.dbPassword()
                );
            }
        );

        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/catalog/sync",
            "{\"dry_run\":false}",
            "operator",
            "secret"
        );

        assertEquals(202, response.statusCode());
        JsonNode failed = waitForCatalogSyncRunState("failed");
        String raw = failed.toString();
        assertFalse(raw.contains(keyId));
        assertFalse(raw.contains(keyPath));
        assertFalse(raw.contains("db-secret"));
        assertTrue(failed.path("latest_run").path("last_error").asText().contains("[redacted]"));
    }

    @Test
    void operatorDemoOrchestratorPostIsDisabledByDefault() throws Exception {
        HttpResponse<String> response = postJson(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"product_readiness_check\"}"
        );

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("operator control POST is disabled"));
    }

    @Test
    void operatorDemoOrchestratorEnabledStillRequiresBasicAuth() throws Exception {
        restartWithOperatorControl(false);

        HttpResponse<String> response = postJson(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"product_readiness_check\"}"
        );

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("requires Basic Auth"));
    }

    @Test
    void operatorDemoOrchestratorIsProtectedByBasicAuthWhenConfigured() throws Exception {
        restartWithOperatorControl(true);

        HttpResponse<String> unauthorized = postJson(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"product_readiness_check\"}"
        );
        HttpResponse<String> authorized = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"product_readiness_check\"}",
            "operator",
            "secret"
        );

        assertEquals(401, unauthorized.statusCode());
        assertEquals(202, authorized.statusCode());
    }

    @Test
    void operatorDemoOrchestratorReplayCheckReturnsConcreteReplayStatus() throws Exception {
        restartWithDemoOperators(
            Map.of("OPENROUTER_API_KEY", ""),
            config -> summary(0, 0, 0, 0, 0, 0),
            Map.of(),
            config -> catalogSummary(0, 0, 0, 0, 0)
        );

        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"replay_demo_check\"}",
            "operator",
            "secret"
        );

        assertEquals(202, response.statusCode());
        JsonNode completed = waitForDemoRunState("completed");
        JsonNode summary = completed.path("latest_run").path("summary");
        assertEquals("replay_demo_check", completed.path("latest_run").path("action").asText());
        assertEquals("replay_demo", completed.path("latest_run").path("mode").asText());
        assertEquals("/api/demo/replay/status", completed.path("latest_run").path("evidence_url").asText());
        assertEquals(
            "seeded",
            summary.path("replay_demo").path("status").asText()
        );
        assertEquals(
            366L,
            summary.path("replay_demo").path("canonical_event_count").asLong()
        );
        assertTrue(summary.path("replay_demo_status").path("replay_demo").isObject());
        assertTrue(summary.path("status_snapshot").isObject());
    }

    @Test
    void operatorDemoOrchestratorStartsSafeSemanticDryRunAndPersistsLatestStatus() throws Exception {
        AtomicReference<SemanticMetadataConfig> observed = new AtomicReference<>();
        restartWithDemoOperators(
            Map.of("OPENROUTER_API_KEY", ""),
            config -> {
                observed.set(config);
                return summary(1, 0, 0, 0, 0, 1);
            },
            Map.of(),
            config -> catalogSummary(0, 0, 0, 0, 0)
        );

        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"semantic_metadata_dry_run\",\"semantic\":{\"max_markets\":1,\"max_tokens\":512}}",
            "operator",
            "secret"
        );

        assertEquals(202, response.statusCode());
        JsonNode completed = waitForDemoRunState("completed");
        assertEquals("semantic_metadata_dry_run", completed.path("latest_run").path("action").asText());
        assertEquals("semantic_metadata", completed.path("latest_run").path("mode").asText());
        assertEquals("scheduled semantic metadata dry-run",
            completed.path("latest_run").path("stdout_summary").asText());
        assertTrue(completed.path("latest_run").path("config").path("llm_dry_run").asBoolean());
        assertEquals("/operator/semantic-metadata/run-status",
            completed.path("latest_run").path("evidence_url").asText());
        assertTrue(observed.get().dryRun());
        assertEquals(1, observed.get().maxMarkets());
        assertEquals(512, observed.get().maxTokens());
        assertEquals("", observed.get().openRouterApiKey());

        JsonNode status = MAPPER.readTree(getWithBasicAuth(
            "/operator/status",
            "operator",
            "secret"
        ).body());
        JsonNode health = MAPPER.readTree(getWithBasicAuth("/health", "operator", "secret").body());
        assertEquals("semantic_metadata_dry_run",
            status.path("demo_orchestrator").path("latest_run").path("action").asText());
        assertEquals("completed", health.path("demo_orchestrator").path("latest_run").path("state").asText());
    }

    @Test
    void operatorDemoOrchestratorSchedulesCatalogDryRunWithSafeDefaults() throws Exception {
        AtomicReference<CatalogSyncOperatorService.CatalogSyncRunConfig> observed = new AtomicReference<>();
        restartWithDemoOperators(
            Map.of("OPENROUTER_API_KEY", ""),
            config -> summary(0, 0, 0, 0, 0, 0),
            Map.of("KALSHI_KEY_ID", "", "KALSHI_KEY_PATH", ""),
            config -> {
                observed.set(config);
                return catalogSummary(1, 3, 2, 0, 2);
            }
        );

        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            """
                {
                  "action": "catalog_sync_dry_run",
                  "catalog": {"dry_run": false, "limit": 25, "max_pages": 2, "max_tickers": 3}
                }
                """,
            "operator",
            "secret"
        );

        assertEquals(202, response.statusCode());
        JsonNode completed = waitForDemoRunState("completed");
        assertEquals("catalog_sync_dry_run", completed.path("latest_run").path("action").asText());
        assertEquals("scheduled catalog sync dry-run", completed.path("latest_run").path("stdout_summary").asText());
        assertTrue(completed.path("latest_run").path("config").path("catalog_dry_run").asBoolean());
        assertTrue(observed.get().request().dryRun());
        assertEquals(25, observed.get().request().limit());
        assertEquals(2, observed.get().request().maxPages());
        assertEquals(3, observed.get().request().maxTickers());
    }

    @Test
    void operatorDemoOrchestratorRejectsUnsafeOrUnsupportedRequests() throws Exception {
        restartWithOperatorControl(true);

        HttpResponse<String> unknown = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"shell\"}",
            "operator",
            "secret"
        );
        HttpResponse<String> command = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"product_readiness_check\",\"command\":\"touch /tmp/pwn\"}",
            "operator",
            "secret"
        );
        HttpResponse<String> liveWithoutConfirm = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"live_product_check\"}",
            "operator",
            "secret"
        );

        assertEquals(400, unknown.statusCode());
        assertTrue(unknown.body().contains("unsupported demo orchestrator action"));
        assertEquals(400, command.statusCode());
        assertTrue(command.body().contains("does not accept shell/env/secret"));
        assertEquals(400, liveWithoutConfirm.statusCode());
        assertTrue(liveWithoutConfirm.body().contains("confirm_live=true"));
    }

    @Test
    void operatorDemoOrchestratorLiveCredentialCheckRequiresConfirmAndRedactsMissingCredentials() throws Exception {
        restartWithDemoOperators(
            Map.of("OPENROUTER_API_KEY", ""),
            config -> summary(0, 0, 0, 0, 0, 0),
            Map.of(),
            config -> catalogSummary(0, 0, 0, 0, 0),
            Map.of(),
            config -> KalshiLiveCredentialPreflight.LiveCredentialCheckResult.failure(
                config.configured(),
                "credentials_missing",
                null,
                "missing key"
            )
        );

        HttpResponse<String> withoutConfirm = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"live_credential_check\"}",
            "operator",
            "secret"
        );
        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"live_credential_check\",\"confirm_live\":true}",
            "operator",
            "secret"
        );

        assertEquals(400, withoutConfirm.statusCode());
        assertTrue(withoutConfirm.body().contains("confirm_live=true"));
        assertEquals(202, response.statusCode());
        JsonNode completed = waitForDemoRunState("completed");
        JsonNode preflight = completed.path("latest_run").path("summary").path("live_credential_preflight");
        assertEquals("live_credential_check", completed.path("latest_run").path("action").asText());
        assertEquals("credentials_missing", preflight.path("failure_category").asText());
        assertFalse(preflight.path("auth_ok").asBoolean());
        assertFalse(preflight.path("configured").asBoolean());
    }

    @Test
    void operatorDemoOrchestratorLiveCredentialCheckReportsAuthOkWithoutSecrets() throws Exception {
        String keyId = "live-check-key-000000000000000000000000";
        String keyPath = "/run/secrets/live-check-key.pem";
        restartWithDemoOperators(
            Map.of("OPENROUTER_API_KEY", ""),
            config -> summary(0, 0, 0, 0, 0, 0),
            Map.of(),
            config -> catalogSummary(0, 0, 0, 0, 0),
            Map.of("KALSHI_KEY_ID", keyId, "KALSHI_KEY_PATH", keyPath, "KALSHI_BASE_URL", "https://kalshi.example"),
            config -> KalshiLiveCredentialPreflight.LiveCredentialCheckResult.success(200, 1, "KXDEMO-YES")
        );

        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"live_credential_check\",\"confirm_live\":true}",
            "operator",
            "secret"
        );

        assertEquals(202, response.statusCode());
        JsonNode completed = waitForDemoRunState("completed");
        JsonNode preflight = completed.path("latest_run").path("summary").path("live_credential_preflight");
        assertTrue(preflight.path("auth_ok").asBoolean());
        assertEquals(200, preflight.path("http_status").asInt());
        assertEquals(1, preflight.path("market_count").asInt());
        assertEquals("KXDEMO-YES", preflight.path("sample_ticker").asText());
        String raw = completed.toString() + response.body();
        assertFalse(raw.contains(keyId));
        assertFalse(raw.contains(keyPath));
    }

    @Test
    void operatorDemoOrchestratorLiveCatalogSyncBoundedRequiresConfirmCapsAndKeepsDryRunExplicit()
        throws Exception {
        AtomicReference<CatalogSyncOperatorService.CatalogSyncRunConfig> observed = new AtomicReference<>();
        restartWithDemoOperators(
            Map.of("OPENROUTER_API_KEY", ""),
            config -> summary(0, 0, 0, 0, 0, 0),
            Map.of("KALSHI_KEY_ID", "catalog-live-key", "KALSHI_KEY_PATH", "/run/secrets/catalog-live.pem"),
            config -> {
                observed.set(config);
                return catalogSummary(1, 2, 2, 2, 0);
            },
            Map.of("KALSHI_KEY_ID", "catalog-live-key", "KALSHI_KEY_PATH", "/run/secrets/catalog-live.pem"),
            config -> KalshiLiveCredentialPreflight.LiveCredentialCheckResult.success(200, 1, "KXCAT")
        );

        HttpResponse<String> withoutConfirm = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"live_catalog_sync_bounded\"}",
            "operator",
            "secret"
        );
        HttpResponse<String> overCap = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            """
                {
                  "action": "live_catalog_sync_bounded",
                  "confirm_live": true,
                  "catalog": {"limit": 101}
                }
                """,
            "operator",
            "secret"
        );
        HttpResponse<String> valid = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            """
                {
                  "action": "live_catalog_sync_bounded",
                  "confirm_live": true,
                  "catalog": {"dry_run": false, "limit": 10, "max_pages": 2, "max_tickers": 7}
                }
                """,
            "operator",
            "secret"
        );

        assertEquals(400, withoutConfirm.statusCode());
        assertEquals(400, overCap.statusCode());
        assertTrue(overCap.body().contains("limit must be <= 100"));
        assertEquals(202, valid.statusCode());
        JsonNode completed = waitForDemoRunState("completed");
        waitForCatalogSyncRunState("completed");
        assertEquals("live_catalog_sync_bounded", completed.path("latest_run").path("action").asText());
        assertFalse(completed.path("latest_run").path("config").path("catalog_dry_run").asBoolean());
        assertEquals(10, completed.path("latest_run").path("summary").path("catalog_bounds").path("limit").asInt());
        assertEquals(7, observed.get().request().maxTickers());
        assertFalse(observed.get().request().dryRun());
    }

    @Test
    void operatorDemoOrchestratorS3PreflightRequiresConfirmAndRedactsConfig() throws Exception {
        String bucket = "kalshi-private-recording-bucket";
        String prefix = "capture/live/private-prefix";
        restartWithDemoOperators(
            Map.of("OPENROUTER_API_KEY", ""),
            config -> summary(0, 0, 0, 0, 0, 0),
            Map.of(),
            config -> catalogSummary(0, 0, 0, 0, 0),
            Map.of("S3_RECORDING_BUCKET", bucket, "AWS_REGION", "us-east-1", "S3_RECORDING_PREFIX", prefix),
            config -> KalshiLiveCredentialPreflight.LiveCredentialCheckResult.failure(false, "credentials_missing", null, "")
        );

        HttpResponse<String> withoutConfirm = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"s3_preflight_check\"}",
            "operator",
            "secret"
        );
        HttpResponse<String> response = postJsonWithBasicAuth(
            "/operator/demo-orchestrator/run",
            "{\"action\":\"s3_preflight_check\",\"confirm_live\":true}",
            "operator",
            "secret"
        );

        assertEquals(400, withoutConfirm.statusCode());
        assertEquals(202, response.statusCode());
        JsonNode completed = waitForDemoRunState("completed");
        JsonNode s3 = completed.path("latest_run").path("summary").path("s3_preflight");
        assertEquals("configured_but_unverified", s3.path("status").asText());
        assertFalse(s3.path("verified").asBoolean());
        assertTrue(s3.path("bucket_configured").asBoolean());
        String raw = completed.toString() + response.body();
        assertFalse(raw.contains(bucket));
        assertFalse(raw.contains(prefix));
    }

    @Test
    void operatorControlPlaneS3StatusIsDisplaySafeAndUnverified() {
        String bucket = "kalshi-sensitive-bucket";
        String prefix = "live/capture/private";
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0"));
        OperatorControlPlane controlPlane = new OperatorControlPlane(
            config,
            FrontendReleaseInfo.empty(),
            Map.of("S3_RECORDING_BUCKET", bucket, "AWS_REGION", "us-east-1", "S3_RECORDING_PREFIX", prefix)
        );

        JsonNode s3 = MAPPER.valueToTree(controlPlane.configurationStatus()).path("s3");

        assertTrue(s3.path("bucket_configured").asBoolean());
        assertEquals("configured_but_unverified", s3.path("status").asText());
        assertFalse(s3.path("verified").asBoolean());
        assertFalse(s3.toString().contains(bucket));
        assertFalse(s3.toString().contains(prefix));
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
    void metricsServesDashboardForBrowserAcceptAndRawPrometheusForFormat() throws Exception {
        HttpResponse<String> dashboard = client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/metrics"))
                .header("Accept", "text/html")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, dashboard.statusCode());
        assertTrue(dashboard.headers().firstValue("content-type").orElse("").contains("text/html"));
        assertTrue(dashboard.body().contains("Kalshi Ops Metrics"));
        assertTrue(dashboard.body().contains("metrics.js"));

        HttpResponse<String> raw = client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/metrics?format=prometheus"))
                .header("Accept", "text/html")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, raw.statusCode());
        assertTrue(raw.headers().firstValue("content-type").orElse("").contains("text/plain"));
        assertTrue(raw.body().contains("frontend_adapter_symbols"));
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

    private void restartWithReplayDemoStatus(ReplayDemoStatus status) throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        seed(store);
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_PORT", "0"));
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.loaded("test", List.of(
                metadata("MKT-1", "EVENT-1", "SERIES-1", "open"),
                metadata("MKT-META", "EVENT-META", "SERIES-META", "closed")
            )),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            OperatorPipelineStatus::disabled,
            sourceEventId -> OperatorLatencyStatus.disabled(),
            () -> OperatorSemanticMetadataStatus.disabled("model", "fallback", "tax-v1"),
            request -> List.of(),
            () -> status,
            FrontendReleaseInfo.empty()
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
    }

    private void restartWithSemanticOperator(
        Map<String, String> serviceEnv,
        Function<SemanticMetadataConfig, SemanticMetadataBatchSummary> runner
    ) throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        seed(store);
        java.util.HashMap<String, String> env = new java.util.HashMap<>();
        env.put("FRONTEND_ADAPTER_PORT", "0");
        env.put("FRONTEND_ADAPTER_OPERATOR_CONTROL_ENABLED", "true");
        env.put("FRONTEND_ADAPTER_BASIC_AUTH_USER", "operator");
        env.put("FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD", "secret");
        env.put("FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://db/kalshi");
        env.put("FRONTEND_ADAPTER_DB_USER", "frontend");
        env.put("FRONTEND_ADAPTER_DB_PASSWORD", "db-secret");
        FrontendAdapterConfig config = FrontendAdapterConfig.from(env);
        java.util.HashMap<String, String> runEnv = new java.util.HashMap<>(serviceEnv);
        if (!runEnv.containsKey("OPENROUTER_API_KEY")) {
            runEnv.put("OPENROUTER_API_KEY", "sk-server-test-key-000000000000000000000000");
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SemanticMetadataOperatorService operatorService =
            new SemanticMetadataOperatorService(config, runEnv, executor, runner);
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            OperatorPipelineStatus::disabled,
            sourceEventId -> OperatorLatencyStatus.disabled(),
            () -> OperatorSemanticMetadataStatus.disabled("model", "fallback", "tax-v1"),
            request -> List.of(),
            FrontendReleaseInfo.empty(),
            operatorService
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
    }

    private void restartWithCatalogSyncOperator(
        Map<String, String> serviceEnv,
        Function<CatalogSyncOperatorService.CatalogSyncRunConfig, CatalogSyncSummary> runner
    ) throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        seed(store);
        java.util.HashMap<String, String> env = new java.util.HashMap<>();
        env.put("FRONTEND_ADAPTER_PORT", "0");
        env.put("FRONTEND_ADAPTER_OPERATOR_CONTROL_ENABLED", "true");
        env.put("FRONTEND_ADAPTER_BASIC_AUTH_USER", "operator");
        env.put("FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD", "secret");
        env.put("FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://db/kalshi");
        env.put("FRONTEND_ADAPTER_DB_USER", "frontend");
        env.put("FRONTEND_ADAPTER_DB_PASSWORD", "db-secret");
        FrontendAdapterConfig config = FrontendAdapterConfig.from(env);
        java.util.HashMap<String, String> runEnv = new java.util.HashMap<>(serviceEnv);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CatalogSyncOperatorService catalogSyncOperator =
            new CatalogSyncOperatorService(config, runEnv, executor, runner);
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            OperatorPipelineStatus::disabled,
            sourceEventId -> OperatorLatencyStatus.disabled(),
            () -> OperatorSemanticMetadataStatus.disabled("model", "fallback", "tax-v1"),
            request -> List.of(),
            FrontendReleaseInfo.empty(),
            null,
            catalogSyncOperator
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
    }

    private void restartWithDemoOperators(
        Map<String, String> semanticEnv,
        Function<SemanticMetadataConfig, SemanticMetadataBatchSummary> semanticRunner,
        Map<String, String> catalogEnv,
        Function<CatalogSyncOperatorService.CatalogSyncRunConfig, CatalogSyncSummary> catalogRunner
    ) throws Exception {
        restartWithDemoOperators(
            semanticEnv,
            semanticRunner,
            catalogEnv,
            catalogRunner,
            Map.of(),
            config -> KalshiLiveCredentialPreflight.LiveCredentialCheckResult.failure(
                config.configured(),
                "credentials_missing",
                null,
                "test credentials are not configured"
            )
        );
    }

    private void restartWithDemoOperators(
        Map<String, String> semanticEnv,
        Function<SemanticMetadataConfig, SemanticMetadataBatchSummary> semanticRunner,
        Map<String, String> catalogEnv,
        Function<CatalogSyncOperatorService.CatalogSyncRunConfig, CatalogSyncSummary> catalogRunner,
        Map<String, String> liveEnv,
        KalshiLiveCredentialPreflight.LiveCredentialChecker liveCredentialChecker
    ) throws Exception {
        server.stop();
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        seed(store);
        java.util.HashMap<String, String> env = new java.util.HashMap<>();
        env.put("FRONTEND_ADAPTER_PORT", "0");
        env.put("FRONTEND_ADAPTER_OPERATOR_CONTROL_ENABLED", "true");
        env.put("FRONTEND_ADAPTER_BASIC_AUTH_USER", "operator");
        env.put("FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD", "secret");
        env.put("FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://db/kalshi");
        env.put("FRONTEND_ADAPTER_DB_USER", "frontend");
        env.put("FRONTEND_ADAPTER_DB_PASSWORD", "db-secret");
        FrontendAdapterConfig config = FrontendAdapterConfig.from(env);
        SemanticMetadataOperatorService semanticService =
            new SemanticMetadataOperatorService(config, semanticEnv, Executors.newSingleThreadExecutor(), semanticRunner);
        CatalogSyncOperatorService catalogService =
            new CatalogSyncOperatorService(config, catalogEnv, Executors.newSingleThreadExecutor(), catalogRunner);
        DemoOrchestratorService demoOrchestrator = new DemoOrchestratorService(
            config,
            new FrontendReleaseInfo("demo-sha", "kalshi-project:demo", "db-primary-product", "1", "1"),
            catalogService,
            semanticService,
            () -> Map.of(),
            () -> Map.of("replay_demo", Map.of(
                "status", "seeded",
                "replay_id", "demo-db-primary-long-replay",
                "canonical_event_count", 366L,
                "feature_output_count", 0L
            )),
            Executors.newSingleThreadExecutor(),
            liveEnv,
            liveCredentialChecker
        );
        server = new FrontendAdapterServer(
            config,
            store,
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            OperatorPipelineStatus::disabled,
            sourceEventId -> OperatorLatencyStatus.disabled(),
            () -> OperatorSemanticMetadataStatus.disabled("model", "fallback", "tax-v1"),
            request -> List.of(),
            new FrontendReleaseInfo("demo-sha", "kalshi-project:demo", "db-primary-product", "1", "1"),
            semanticService,
            catalogService,
            demoOrchestrator
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
    }

    private JsonNode waitForSemanticRunState(String expectedState) throws Exception {
        JsonNode status = null;
        for (int attempt = 0; attempt < 80; attempt++) {
            status = MAPPER.readTree(getWithBasicAuth(
                "/operator/semantic-metadata/run-status",
                "operator",
                "secret"
            ).body());
            if (expectedState.equals(status.path("latest_run").path("state").asText())) {
                return status;
            }
            Thread.sleep(25L);
        }
        assertNotNull(status);
        return status;
    }

    private JsonNode waitForCatalogSyncRunState(String expectedState) throws Exception {
        JsonNode status = null;
        for (int attempt = 0; attempt < 80; attempt++) {
            status = MAPPER.readTree(getWithBasicAuth(
                "/operator/catalog/sync-status",
                "operator",
                "secret"
            ).body());
            if (expectedState.equals(status.path("latest_run").path("state").asText())) {
                return status;
            }
            Thread.sleep(25L);
        }
        assertNotNull(status);
        return status;
    }

    private JsonNode waitForDemoRunState(String expectedState) throws Exception {
        JsonNode status = null;
        for (int attempt = 0; attempt < 80; attempt++) {
            status = MAPPER.readTree(getWithBasicAuth(
                "/operator/demo-orchestrator/run-status",
                "operator",
                "secret"
            ).body());
            if (expectedState.equals(status.path("latest_run").path("state").asText())) {
                return status;
            }
            Thread.sleep(25L);
        }
        assertNotNull(status);
        return status;
    }

    private static SemanticMetadataBatchSummary summary(
        int processed,
        int generated,
        int reviewRequired,
        int rateLimited,
        int failed,
        int skipped
    ) {
        return new SemanticMetadataBatchSummary(
            processed,
            generated,
            reviewRequired,
            rateLimited,
            failed,
            skipped,
            0,
            BigDecimal.ZERO,
            "primary",
            "fallback"
        );
    }

    private static CatalogSyncSummary catalogSummary(
        int pagesFetched,
        int marketsDiscovered,
        int marketsSelected,
        int rowsUpserted,
        int dryRunRows
    ) {
        return new CatalogSyncSummary(
            pagesFetched,
            marketsDiscovered,
            marketsSelected,
            rowsUpserted,
            dryRunRows,
            0,
            "",
            false
        );
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

    private static JsonNode findMarket(JsonNode markets, String marketTicker) {
        for (JsonNode market : markets) {
            if (marketTicker.equals(market.path("market_ticker").asText())) {
                return market;
            }
        }
        throw new AssertionError("missing market " + marketTicker);
    }

    private static boolean containsSymbol(JsonNode symbols, String symbol) {
        for (JsonNode entry : symbols) {
            if (symbol.equals(entry.path("symbol").asText())) {
                return true;
            }
        }
        return false;
    }

    private FakeSemanticMarketMetadataReader restartWithSemanticReader(List<SemanticMarketMetadataRow> rows)
        throws Exception {
        FakeSemanticMarketMetadataReader reader = new FakeSemanticMarketMetadataReader(rows);
        restartWithSemanticReader((SemanticMarketMetadataReader) reader);
        return reader;
    }

    private void restartWithSemanticReader(SemanticMarketMetadataReader reader) throws Exception {
        server.stop();
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://db/kalshi",
            "FRONTEND_ADAPTER_SEMANTIC_METADATA_STATUS_SOURCE", "db",
            "LLM_METADATA_TAXONOMY_VERSION", "tax-v1"
        ));
        server = semanticServer(config, reader);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
    }

    private FrontendAdapterServer semanticServer(
        FrontendAdapterConfig config,
        SemanticMarketMetadataReader reader
    ) {
        return new FrontendAdapterServer(
            config,
            new FrontendFeatureStore(100, 100),
            FrontendMarketMetadataCatalog.disabled("test"),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            FeatureOutputRefreshStatus::disabled,
            OperatorPipelineStatus::disabled,
            sourceEventId -> OperatorLatencyStatus.disabled(),
            () -> new OperatorSemanticMetadataStatus(
                "ok",
                true,
                "model",
                "fallback",
                config.llmMetadataTaxonomyVersion(),
                1L,
                0L,
                0L,
                0L,
                Instant.parse("2026-05-20T00:00:00Z"),
                10L,
                null
            ),
            reader,
            FrontendReleaseInfo.empty()
        );
    }

    private FakeFeatureOutputReader restartWithDbHistory(String marketTicker, List<FeatureOutput> rows)
        throws Exception {
        server.stop();
        FakeFeatureOutputReader reader = new FakeFeatureOutputReader(rows);
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_PORT", "0",
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "latest_market_state",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://db/kalshi"
        ));
        server = new FrontendAdapterServer(
            config,
            new FrontendFeatureStore(100, 100),
            FrontendMarketMetadataCatalog.loaded("test", List.of(
                metadata(marketTicker, "EVENT-" + marketTicker, "SERIES-" + marketTicker, "open")
            )),
            () -> FrontendAdapterServer.FeaturePlantStats.EMPTY,
            reader
        );
        server.start();
        baseUrl = "http://127.0.0.1:" + server.boundPort();
        return reader;
    }

    private static List<FeatureOutput> dbHistoryOutputs(
        String marketTicker,
        String featureName,
        long startMs,
        String priceField
    ) {
        List<FeatureOutput> rows = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            rows.add(new FeatureOutput(
                featureName,
                featureName,
                marketTicker,
                startMs + index * 60_000L,
                "db-history-" + featureName + "-" + index,
                Map.of(priceField, 420_000L + index * 1_000L)
            ));
        }
        return List.copyOf(rows);
    }

    private static final class FakeFeatureOutputReader implements FeatureOutputReader {
        private final List<FeatureOutput> rows;
        private final List<FeatureOutputReadRequest> requests = new ArrayList<>();

        private FakeFeatureOutputReader(List<FeatureOutput> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        public List<FeatureOutput> read(FeatureOutputReadRequest request) {
            requests.add(request);
            return rows.stream()
                .filter(row -> request.marketTicker() == null || request.marketTicker().equals(row.marketTicker()))
                .filter(row -> request.featureNames().isEmpty() || request.featureNames().contains(row.featureName()))
                .filter(row -> request.fromEventTsMs() == null || row.eventTsMs() >= request.fromEventTsMs())
                .filter(row -> request.toEventTsMs() == null || row.eventTsMs() <= request.toEventTsMs())
                .limit(request.maxRows())
                .toList();
        }
    }

    private static JsonNode group(JsonNode groups, String key) {
        for (JsonNode group : groups) {
            if (key.equals(group.path("key").asText())) {
                return group;
            }
        }
        throw new AssertionError("missing group " + key + ": " + groups);
    }

    private static SemanticMarketMetadataRow semanticRow(String marketTicker, String sector) {
        boolean weather = "weather".equals(sector);
        return semanticRow(
            marketTicker,
            sector,
            weather ? "forecast" : "election",
            weather ? List.of("forecast", "weather") : List.of("election"),
            weather ? 123L : 45L
        );
    }

    private static SemanticMarketMetadataRow semanticRow(
        String marketTicker,
        String sector,
        String eventType,
        List<String> tags,
        long openInterest
    ) {
        boolean weather = "weather".equals(sector);
        return new SemanticMarketMetadataRow(
            marketTicker,
            weather ? "EVENT-WEATHER" : "EVENT-" + sector.toUpperCase(Locale.ROOT),
            weather ? "SERIES-WEATHER" : "SERIES-" + sector.toUpperCase(Locale.ROOT),
            "open",
            weather ? "Will it rain?" : "Who wins?",
            "tax-v1",
            "model",
            "prompt-v1",
            "generated",
            sector,
            weather ? "rain" : eventType,
            eventType,
            "us",
            "daily",
            "high",
            "low",
            tags,
            new BigDecimal(weather ? "0.83" : "0.72"),
            "classification rationale",
            Instant.parse("2026-05-20T00:00:00Z"),
            Instant.parse("2026-05-20T00:00:01Z"),
            20L,
            10L,
            1_000L,
            "canonical-1",
            42L,
            440_000L,
            460_000L,
            450_000L,
            openInterest,
            Instant.parse("2026-05-20T00:00:02Z"),
            5L
        );
    }

    private static final class FakeSemanticMarketMetadataReader implements SemanticMarketMetadataReader {
        private final List<SemanticMarketMetadataRow> rows;
        private final List<SemanticMarketMetadataReadRequest> requests = new ArrayList<>();

        private FakeSemanticMarketMetadataReader(List<SemanticMarketMetadataRow> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        public List<SemanticMarketMetadataRow> read(SemanticMarketMetadataReadRequest request) {
            requests.add(request);
            return rows;
        }
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
        long start = System.currentTimeMillis() - 900_000L;
        for (int index = 0; index < 10; index++) {
            long ts = start + (index * 60_000L);
            store.accept(new FeatureOutput(
                FrontendFeatureStore.BBO_FEATURE,
                FrontendFeatureStore.BBO_FEATURE,
                "MKT-ELIGIBLE",
                ts,
                "recent-evt-" + index,
                Map.of(
                    "bid_price_micros", 450_000L + index,
                    "ask_price_micros", 550_000L + index,
                    "midpoint_micros", 500_000L + index
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
