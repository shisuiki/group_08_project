package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.storage.db.MarketMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void symbolsAndQuotesShape() throws Exception {
        JsonNode symbols = getJson("/symbols");
        assertTrue(symbols.path("symbols").isArray());
        assertTrue(symbols.path("symbols").size() >= 1);
        JsonNode quotes = getJson("/quotes?symbols=MKT-1,MKT-MISSING");
        assertEquals(2, quotes.path("quotes").size());
        assertEquals("MKT-1", quotes.path("quotes").get(0).path("symbol").asText());
        assertNotNull(quotes.path("quotes").get(0).path("midpoint_micros"));
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
        assertEquals(42L, body.path("feature_plant").path("events_in").asLong());
        assertEquals(17L, body.path("feature_plant").path("events_out").asLong());
    }

    @Test
    void metricsExposesRequiredKeys() throws Exception {
        getJson("/symbols");
        HttpResponse<String> metrics = get("/metrics");
        String body = metrics.body();
        assertTrue(body.contains("frontend_adapter_symbols"));
        assertTrue(body.contains("frontend_adapter_features_total"));
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
}
