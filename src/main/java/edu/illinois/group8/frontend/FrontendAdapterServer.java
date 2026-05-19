package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.MarketMetadata;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public class FrontendAdapterServer {
    private static final int DEFAULT_FEATURE_LIMIT = 100;
    private static final int MAX_FEATURE_LIMIT = 500;
    private static final int DEFAULT_MARKET_LIMIT = 100;
    private static final int MAX_MARKET_LIMIT = 500;

    public record FeaturePlantStats(long eventsIn, long eventsOut, long errors) {
        public static final FeaturePlantStats EMPTY = new FeaturePlantStats(0L, 0L, 0L);
    }

    private final FrontendAdapterConfig config;
    private final FrontendFeatureStore store;
    private final FrontendMarketMetadataCatalog metadataCatalog;
    private final Supplier<FeaturePlantStats> featurePlantStats;
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();
    private final BackendMetrics metrics = new BackendMetrics();
    private final ConcurrentHashMap<String, LongAdder> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> requestDurationCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> requestDurationNanosSum = new ConcurrentHashMap<>();
    private final long startedAtMs = System.currentTimeMillis();
    private HttpServer httpServer;

    public FrontendAdapterServer(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        Supplier<FeaturePlantStats> featurePlantStats
    ) {
        this(config, store, FrontendMarketMetadataCatalog.disabled("disabled"), featurePlantStats);
    }

    public FrontendAdapterServer(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        FrontendMarketMetadataCatalog metadataCatalog,
        Supplier<FeaturePlantStats> featurePlantStats
    ) {
        this.config = config;
        this.store = store;
        this.metadataCatalog = metadataCatalog == null
            ? FrontendMarketMetadataCatalog.disabled("disabled")
            : metadataCatalog;
        this.featurePlantStats = featurePlantStats == null ? () -> FeaturePlantStats.EMPTY : featurePlantStats;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        bind("/datafeed/config", this::handleDatafeedConfig);
        bind("/datafeed/symbols", this::handleDatafeedSymbols);
        bind("/datafeed/search", this::handleDatafeedSearch);
        bind("/datafeed/history", this::handleDatafeedHistory);
        bind("/datafeed/time", this::handleDatafeedTime);
        bind("/symbols", this::handleSymbols);
        bind("/quotes", this::handleQuotes);
        bind("/features", this::handleFeatures);
        bind("/markets", this::handleMarkets);
        bind("/health", this::handleHealth);
        bind("/metrics", this::handleMetrics);
        httpServer.setExecutor(Executors.newFixedThreadPool(4));
        httpServer.start();
    }

    public int boundPort() {
        return httpServer == null ? -1 : httpServer.getAddress().getPort();
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
            httpServer = null;
        }
    }

    private void bind(String path, HttpHandler delegate) {
        httpServer.createContext(path, exchange -> instrument(path, exchange, delegate));
    }

    private void instrument(String path, HttpExchange exchange, HttpHandler delegate) throws IOException {
        requestCounters.computeIfAbsent(path, key -> new LongAdder()).increment();
        requestDurationCount.computeIfAbsent(path, key -> new LongAdder()).increment();
        AtomicLong sum = requestDurationNanosSum.computeIfAbsent(path, key -> new AtomicLong());
        applyCors(exchange);
        long startNs = System.nanoTime();
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            delegate.handle(exchange);
        } catch (RuntimeException e) {
            writeError(exchange, 500, e.getMessage());
        } finally {
            sum.addAndGet(System.nanoTime() - startNs);
        }
    }

    private void handleDatafeedConfig(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supported_resolutions", BarResolution.SUPPORTED);
        body.put("supports_search", true);
        body.put("supports_group_request", false);
        body.put("supports_marks", false);
        body.put("supports_timescale_marks", false);
        body.put("supports_time", true);
        writeJson(exchange, 200, body);
    }

    private void handleDatafeedSymbols(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String symbol = params.getOrDefault("symbol", "");
        if (symbol.isBlank()) {
            writeError(exchange, 400, "symbol is required");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", symbol);
        body.put("ticker", symbol);
        Optional<MarketMetadata> metadata = metadataCatalog.find(symbol);
        body.put("description", metadata.map(FrontendAdapterServer::metadataDescription).orElse(symbol));
        body.put("type", "binary");
        body.put("session", "24x7");
        body.put("timezone", "Etc/UTC");
        body.put("minmov", 1);
        body.put("pricescale", 1_000_000);
        body.put("has_intraday", true);
        body.put("has_seconds", true);
        body.put("supported_resolutions", BarResolution.SUPPORTED);
        body.put("volume_precision", 0);
        metadata.ifPresent(row -> addMetadataFields(body, row));
        writeJson(exchange, 200, body);
    }

    private void handleDatafeedSearch(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String query = params.getOrDefault("query", "").toLowerCase(Locale.ROOT);
        int limit;
        try {
            limit = Math.min(200, Math.max(1, Integer.parseInt(params.getOrDefault("limit", "30"))));
        } catch (NumberFormatException e) {
            limit = 30;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        for (MarketMetadata metadata : metadataCatalog.search(query, null, limit)) {
            results.add(searchEntry(metadata));
            seen.add(metadata.marketTicker());
            if (results.size() >= limit) {
                break;
            }
        }
        for (String symbol : store.symbols()) {
            if (results.size() >= limit) {
                break;
            }
            if (!seen.add(symbol)) {
                continue;
            }
            if (query.isEmpty() || symbol.toLowerCase(Locale.ROOT).contains(query)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("symbol", symbol);
                entry.put("full_name", symbol);
                entry.put("description", symbol);
                entry.put("exchange", "Kalshi");
                entry.put("ticker", symbol);
                entry.put("type", "binary");
                results.add(entry);
            }
        }
        writeJson(exchange, 200, results);
    }

    private void handleDatafeedHistory(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String symbol = params.getOrDefault("symbol", "");
        String resolutionRaw = params.getOrDefault("resolution", "");
        String fromRaw = params.getOrDefault("from", "");
        String toRaw = params.getOrDefault("to", "");
        if (symbol.isBlank() || resolutionRaw.isBlank() || fromRaw.isBlank() || toRaw.isBlank()) {
            writeError(exchange, 400, "symbol, resolution, from, to are required");
            return;
        }
        BarResolution resolution;
        long fromMs;
        long toMs;
        try {
            resolution = BarResolution.parse(resolutionRaw);
            fromMs = toMillis(Long.parseLong(fromRaw));
            toMs = toMillis(Long.parseLong(toRaw));
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
            return;
        }
        List<Bar> bars = store.bars(symbol, fromMs, toMs, resolution);
        if (bars.isEmpty()) {
            writeJson(exchange, 200, Map.of("s", "no_data"));
            return;
        }
        List<Long> t = new ArrayList<>(bars.size());
        List<Double> o = new ArrayList<>(bars.size());
        List<Double> h = new ArrayList<>(bars.size());
        List<Double> l = new ArrayList<>(bars.size());
        List<Double> c = new ArrayList<>(bars.size());
        List<Long> v = new ArrayList<>(bars.size());
        for (Bar bar : bars) {
            t.add(bar.openTimeMs() / 1000L);
            o.add(bar.open());
            h.add(bar.high());
            l.add(bar.low());
            c.add(bar.close());
            v.add(bar.sampleCount());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("s", "ok");
        body.put("t", t);
        body.put("o", o);
        body.put("h", h);
        body.put("l", l);
        body.put("c", c);
        body.put("v", v);
        writeJson(exchange, 200, body);
    }

    private void handleDatafeedTime(HttpExchange exchange) throws IOException {
        write(exchange, 200, "text/plain; charset=utf-8",
            Long.toString(System.currentTimeMillis() / 1000L));
    }

    private void handleSymbols(HttpExchange exchange) throws IOException {
        Set<String> symbols = store.symbols();
        List<Map<String, Object>> entries = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            Optional<FeatureOutput> latestBbo = store.latest(symbol, FrontendFeatureStore.BBO_FEATURE);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("symbol", symbol);
            entry.put("latest_event_ts_ms", latestBbo.map(FeatureOutput::eventTsMs).orElse(null));
            entries.add(entry);
        }
        writeJson(exchange, 200, Map.of("symbols", entries));
    }

    private void handleQuotes(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String csv = params.getOrDefault("symbols", "");
        List<Map<String, Object>> quotes = new ArrayList<>();
        for (String symbol : csv.split(",")) {
            String trimmed = symbol.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Optional<FeatureOutput> latest = store.latest(trimmed, FrontendFeatureStore.BBO_FEATURE);
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("symbol", trimmed);
            if (latest.isPresent()) {
                FeatureOutput out = latest.get();
                quote.put("bid_micros", out.values().get("bid_price_micros"));
                quote.put("ask_micros", out.values().get("ask_price_micros"));
                quote.put("midpoint_micros", out.values().get("midpoint_micros"));
                quote.put("event_ts_ms", out.eventTsMs());
            } else {
                quote.put("bid_micros", null);
                quote.put("ask_micros", null);
                quote.put("midpoint_micros", null);
                quote.put("event_ts_ms", null);
            }
            quotes.add(quote);
        }
        writeJson(exchange, 200, Map.of("quotes", quotes));
    }

    private void handleFeatures(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String symbol = params.getOrDefault("symbol", "").trim();
        String feature = params.getOrDefault("feature", "").trim();
        if (symbol.isBlank() || feature.isBlank()) {
            writeError(exchange, 400, "symbol and feature are required");
            return;
        }
        int limit;
        try {
            limit = parseLimit(params.get("limit"), DEFAULT_FEATURE_LIMIT, MAX_FEATURE_LIMIT);
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
            return;
        }

        List<FeatureOutput> snapshot = store.snapshot(symbol, feature);
        int fromIndex = Math.max(0, snapshot.size() - limit);
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (FeatureOutput output : snapshot.subList(fromIndex, snapshot.size())) {
            outputs.add(featureOutputBody(output));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("feature", feature);
        body.put("count", outputs.size());
        body.put("outputs", outputs);
        writeJson(exchange, 200, body);
    }

    private void handleMarkets(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        int limit;
        try {
            limit = parseLimit(params.get("limit"), DEFAULT_MARKET_LIMIT, MAX_MARKET_LIMIT);
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
            return;
        }
        String query = params.getOrDefault("query", "");
        String status = params.getOrDefault("status", "");
        List<Map<String, Object>> markets = metadataCatalog.search(query, status, limit).stream()
            .map(FrontendAdapterServer::marketMetadataBody)
            .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", markets.size());
        body.put("markets", markets);
        writeJson(exchange, 200, body);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        FeaturePlantStats stats = featurePlantStats.get();
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "ok");
        health.put("service", "frontend-adapter");
        health.put("source_mode", config.sourceMode().name().toLowerCase(Locale.ROOT));
        health.put("feature_source", config.featureSource().name().toLowerCase(Locale.ROOT));
        health.put("started_at", java.time.Instant.ofEpochMilli(startedAtMs).toString());
        health.put("uptime_ms", System.currentTimeMillis() - startedAtMs);
        Map<String, Object> storeView = new LinkedHashMap<>();
        storeView.put("symbols", store.symbolCount());
        storeView.put("total_features", store.totalAccepted());
        health.put("store", storeView);
        Map<String, Object> metadataView = new LinkedHashMap<>();
        metadataView.put("source", metadataCatalog.source());
        metadataView.put("status", metadataCatalog.loadStatus().name().toLowerCase(Locale.ROOT));
        metadataView.put("markets", metadataCatalog.size());
        if (metadataCatalog.error() != null) {
            metadataView.put("error", metadataCatalog.error());
        }
        health.put("market_metadata", metadataView);
        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("events_in", stats.eventsIn());
        fp.put("events_out", stats.eventsOut());
        fp.put("errors", stats.errors());
        health.put("feature_plant", fp);
        writeJson(exchange, 200, health);
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        metrics.setGauge("frontend_adapter_symbols", store.symbolCount());
        metrics.setGauge("frontend_adapter_features_total", store.totalAccepted());
        StringBuilder body = new StringBuilder();
        body.append(metrics.prometheusText());
        requestCounters.forEach((path, counter) -> body
            .append("frontend_adapter_http_requests_total{path=\"")
            .append(escape(path))
            .append("\"} ")
            .append(counter.sum())
            .append('\n'));
        requestDurationCount.forEach((path, count) -> {
            long nanos = requestDurationNanosSum
                .getOrDefault(path, new AtomicLong()).get();
            double seconds = nanos / 1_000_000_000.0;
            body.append("frontend_adapter_http_request_duration_seconds_sum{path=\"")
                .append(escape(path))
                .append("\"} ")
                .append(seconds)
                .append('\n');
            body.append("frontend_adapter_http_request_duration_seconds_count{path=\"")
                .append(escape(path))
                .append("\"} ")
                .append(count.sum())
                .append('\n');
        });
        write(exchange, 200, "text/plain; charset=utf-8", body.toString());
    }

    private static Map<String, Object> featureOutputBody(FeatureOutput output) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("feature_name", output.featureName());
        body.put("market_ticker", output.marketTicker());
        body.put("event_ts_ms", output.eventTsMs());
        body.put("source_event_id", output.sourceEventId());
        body.put("values", output.values());
        return body;
    }

    private static Map<String, Object> marketMetadataBody(MarketMetadata metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("market_ticker", metadata.marketTicker());
        body.put("event_ticker", metadata.eventTicker());
        body.put("series_ticker", metadata.seriesTicker());
        body.put("status", metadata.status());
        body.put("open_time", metadata.openTime() == null ? null : metadata.openTime().toString());
        body.put("close_time", metadata.closeTime() == null ? null : metadata.closeTime().toString());
        body.put("settlement_time", metadata.settlementTime() == null ? null : metadata.settlementTime().toString());
        return body;
    }

    private static Map<String, Object> searchEntry(MarketMetadata metadata) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("symbol", metadata.marketTicker());
        entry.put("full_name", metadata.marketTicker());
        entry.put("description", metadataDescription(metadata));
        entry.put("exchange", "Kalshi");
        entry.put("ticker", metadata.marketTicker());
        entry.put("type", "binary");
        entry.put("status", metadata.status());
        return entry;
    }

    private static String metadataDescription(MarketMetadata metadata) {
        List<String> parts = new ArrayList<>();
        if (metadata.eventTicker() != null && !metadata.eventTicker().isBlank()) {
            parts.add(metadata.eventTicker());
        }
        if (metadata.seriesTicker() != null && !metadata.seriesTicker().isBlank()) {
            parts.add(metadata.seriesTicker());
        }
        if (metadata.status() != null && !metadata.status().isBlank()) {
            parts.add(metadata.status());
        }
        return parts.isEmpty() ? metadata.marketTicker() : String.join(" / ", parts);
    }

    private static void addMetadataFields(Map<String, Object> body, MarketMetadata metadata) {
        body.put("event_ticker", metadata.eventTicker());
        body.put("series_ticker", metadata.seriesTicker());
        body.put("status", metadata.status());
        body.put("open_time", metadata.openTime() == null ? null : metadata.openTime().toString());
        body.put("close_time", metadata.closeTime() == null ? null : metadata.closeTime().toString());
        body.put("settlement_time", metadata.settlementTime() == null ? null : metadata.settlementTime().toString());
    }

    private static int parseLimit(String raw, int defaultLimit, int maxLimit) {
        if (raw == null || raw.isBlank()) {
            return defaultLimit;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be an integer");
        }
        if (parsed < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return Math.min(parsed, maxLimit);
    }

    private static long toMillis(long timestamp) {
        return timestamp > 10_000_000_000L ? timestamp : timestamp * 1000L;
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key;
            String value;
            if (eq < 0) {
                key = decode(pair);
                value = "";
            } else {
                key = decode(pair.substring(0, eq));
                value = decode(pair.substring(eq + 1));
            }
            params.put(key, value);
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
    }

    private void applyCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        write(exchange, status, "application/json; charset=utf-8", mapper.writeValueAsString(body));
    }

    private void writeError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message == null ? "" : message);
        write(exchange, status, "application/json; charset=utf-8", mapper.writeValueAsString(body));
    }

    private void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
