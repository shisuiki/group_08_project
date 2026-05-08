package edu.illinois.group8.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.illinois.group8.canonical.StreamRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class IntegrationGatewayServer implements AutoCloseable {
    private final IntegrationGatewayConfig config;
    private final GatewayEventStore store;
    private final GatewayWebSocketBroadcaster broadcaster;
    private final ReplaySessionManager replaySessions;
    private final ObjectMapper mapper;
    private final HttpServer httpServer;
    private final AeronGatewaySubscriber subscriber;
    private final StorageBackedRecordingConsumer storageConsumer;
    private final long startedAtMs = System.currentTimeMillis();

    public IntegrationGatewayServer(IntegrationGatewayConfig config) throws IOException {
        this.config = config;
        this.store = new GatewayEventStore(config.maxIndexedEvents());
        this.broadcaster = new GatewayWebSocketBroadcaster();
        this.mapper = store.mapper();
        this.replaySessions = new ReplaySessionManager(store, broadcaster, mapper);
        this.httpServer = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        this.subscriber = config.aeronLiveEnabled()
            ? new AeronGatewaySubscriber(config.aeronChannel(), config.streams(), store, broadcaster, mapper)
            : null;
        this.storageConsumer = config.storageTailEnabled()
            ? new StorageBackedRecordingConsumer(config.journalRoot(), store, broadcaster, config.storagePollIntervalMs())
            : null;
        registerContexts();
    }

    public static void main(String[] args) throws Exception {
        IntegrationGatewayServer server = new IntegrationGatewayServer(IntegrationGatewayConfig.fromEnvironment());
        server.start();
    }

    public void start() {
        long loaded = 0L;
        if (config.seedJournalEnabled()) {
            loaded = storageConsumer == null ? store.loadJournal(config.journalRoot()) : storageConsumer.loadInitial();
        }
        httpServer.setExecutor(Executors.newFixedThreadPool(12));
        httpServer.start();
        if (storageConsumer != null) {
            storageConsumer.start();
        }
        if (subscriber != null) {
            subscriber.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        System.out.println("FrontendStreamAdapter listening on " + config.host() + ":" + config.port()
            + " source=recorded_tickerplant_stream"
            + " seed_journal=" + config.seedJournalEnabled()
            + " seed_events=" + loaded
            + " storage_tail_enabled=" + config.storageTailEnabled()
            + " stream_enabled=" + config.aeronLiveEnabled());
    }

    @Override
    public void close() {
        if (subscriber != null) {
            subscriber.close();
        }
        if (storageConsumer != null) {
            storageConsumer.close();
        }
        httpServer.stop(1);
    }

    private void registerContexts() {
        httpServer.createContext("/health", this::handleHealth);
        httpServer.createContext("/metrics", this::handleMetrics);
        httpServer.createContext("/schema", this::handleSchema);
        httpServer.createContext("/symbols", this::handleSymbols);
        httpServer.createContext("/history", this::handleHistory);
        httpServer.createContext("/quotes", this::handleQuotes);
        httpServer.createContext("/depth", this::handleDepth);
        httpServer.createContext("/trades", this::handleTrades);
        httpServer.createContext("/open-interest", this::handleOpenInterest);
        httpServer.createContext("/stream", this::handleStream);
        httpServer.createContext("/replay/sessions", this::handleReplaySessions);
        httpServer.createContext("/datafeed/config", this::handleDatafeedConfig);
        httpServer.createContext("/datafeed/search", this::handleDatafeedSearch);
        httpServer.createContext("/datafeed/symbols", this::handleDatafeedSymbol);
        httpServer.createContext("/datafeed/history", this::handleDatafeedHistory);
        httpServer.createContext("/time", this::handleTime);
        httpServer.createContext("/", this::handleIndex);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("service", "frontend_stream_adapter");
        response.put("source_mode", "storage_backed_recorder");
        response.put("started_at", Instant.ofEpochMilli(startedAtMs).toString());
        response.put("uptime_ms", System.currentTimeMillis() - startedAtMs);
        response.put("seed_journal_enabled", config.seedJournalEnabled());
        if (config.seedJournalEnabled()) {
            response.put("seed_journal_root", config.journalRoot().toString());
        }
        response.put("store", store.stats());
        response.put("websocket_clients", broadcaster.clientCount());
        response.put("websocket_messages_sent", broadcaster.messagesSent());
        response.put("timestamp_source", config.timestampSource().metadata());
        response.put("storage_consumer", storageConsumer == null ? Map.of("running", false) : storageConsumer.stats());
        response.put("aeron", subscriber == null ? Map.of("running", false) : subscriber.stats());
        writeJson(exchange, response);
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, Object> stats = store.stats();
        StringBuilder body = new StringBuilder();
        metric(body, "frontend_adapter_symbols", stats.get("symbols"));
        metric(body, "frontend_adapter_events_total", stats.get("total_events"));
        metric(body, "frontend_adapter_seed_events_total", stats.get("journal_events"));
        metric(body, "frontend_adapter_stream_events_total", stats.get("live_events"));
        metric(body, "frontend_adapter_websocket_clients", broadcaster.clientCount());
        metric(body, "frontend_adapter_websocket_messages_total", broadcaster.messagesSent());
        if (storageConsumer != null) {
            Map<String, Object> storageStats = storageConsumer.stats();
            metric(body, "frontend_adapter_storage_events_total", storageStats.get("events_read"));
            metric(body, "frontend_adapter_storage_files_tracked", storageStats.get("files_tracked"));
            metric(body, "frontend_adapter_storage_scans_total", storageStats.get("scan_count"));
            metric(body, "frontend_adapter_storage_parse_errors_total", storageStats.get("parse_errors"));
            metric(body, "frontend_adapter_storage_read_errors_total", storageStats.get("read_errors"));
        }
        body.append("frontend_adapter_ptp_device_present ")
            .append(Boolean.TRUE.equals(config.timestampSource().metadata().get("ptp_device_present")) ? 1 : 0)
            .append('\n');
        body.append("frontend_adapter_ptp_device_visible_to_process ")
            .append(Boolean.TRUE.equals(config.timestampSource().metadata().get("ptp_device_visible_to_process")) ? 1 : 0)
            .append('\n');
        body.append("frontend_adapter_ptp_host_enabled ")
            .append(Boolean.TRUE.equals(config.timestampSource().metadata().get("ptp_host_enabled")) ? 1 : 0)
            .append('\n');
        write(exchange, "text/plain; charset=utf-8", body.toString());
    }

    private void handleSchema(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schema_version", 1);
        response.put("streams", StreamRegistry.all().stream().map(stream -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stream_name", stream.streamName());
            item.put("stream_id", stream.streamId());
            item.put("schema_version", stream.schemaVersion());
            item.put("owner", stream.owner());
            item.put("serialization_format", stream.serializationFormat());
            item.put("ordering_guarantee", stream.orderingGuarantee());
            item.put("retention_policy", stream.retentionPolicy());
            item.put("replay_available", stream.replayAvailable());
            item.put("external", stream.external());
            return item;
        }).toList());
        writeJson(exchange, response);
    }

    private void handleSymbols(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.length() > "/symbols/".length()) {
            String ticker = decode(path.substring("/symbols/".length()));
            var symbol = store.symbol(ticker);
            if (symbol.isEmpty()) {
                writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, Map.of("error", "symbol not found"));
                return;
            }
            writeJson(exchange, symbol.get());
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        writeJson(exchange, Map.of("symbols", store.symbols(query.getOrDefault("query", ""))));
    }

    private void handleHistory(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        String symbol = required(query, "symbol");
        String resolution = query.getOrDefault("resolution", "1");
        Long fromMs = optionalTime(query.get("from"));
        Long toMs = optionalTime(query.get("to"));
        int limit = intParam(query, "limit", 500);
        writeJson(exchange, store.history(symbol, resolution, fromMs, toMs, limit));
    }

    private void handleQuotes(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        List<String> symbols = csv(required(query, "symbols"));
        writeJson(exchange, store.quotes(symbols));
    }

    private void handleDepth(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        writeJson(exchange, store.depth(required(query, "symbol")));
    }

    private void handleTrades(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        writeJson(exchange, store.trades(
            required(query, "symbol"),
            optionalTime(query.get("from")),
            optionalTime(query.get("to")),
            intParam(query, "limit", 500)
        ));
    }

    private void handleOpenInterest(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        writeJson(exchange, store.openInterest(
            required(query, "symbol"),
            optionalTime(query.get("from")),
            optionalTime(query.get("to")),
            intParam(query, "limit", 500)
        ));
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        broadcaster.handle(exchange);
    }

    private void handleReplaySessions(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            if ("/replay/sessions".equals(path) && "GET".equalsIgnoreCase(method)) {
                writeJson(exchange, Map.of("sessions", replaySessions.list()));
                return;
            }
            if ("/replay/sessions".equals(path) && "POST".equalsIgnoreCase(method)) {
                writeJson(exchange, replaySessions.create(readJson(exchange)));
                return;
            }
            String[] parts = path.split("/");
            if (parts.length == 5 && "POST".equalsIgnoreCase(method)) {
                String id = decode(parts[3]);
                String action = parts[4];
                switch (action) {
                    case "pause" -> writeJson(exchange, replaySessions.pause(id));
                    case "resume" -> writeJson(exchange, replaySessions.resume(id));
                    case "stop" -> writeJson(exchange, replaySessions.stop(id));
                    case "seek" -> writeJson(exchange, replaySessions.seek(id, readJson(exchange)));
                    default -> writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, Map.of("error", "unknown replay action"));
                }
                return;
            }
            writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, Map.of("error", "unknown replay endpoint"));
        } catch (IllegalArgumentException e) {
            writeJson(exchange, HttpURLConnection.HTTP_BAD_REQUEST, Map.of("error", e.getMessage()));
        }
    }

    private void handleDatafeedConfig(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("supports_search", true);
        config.put("supports_group_request", false);
        config.put("supports_marks", false);
        config.put("supports_timescale_marks", false);
        config.put("supports_time", true);
        config.put("supported_resolutions", List.of("1S", "1", "5", "60", "1D"));
        writeJson(exchange, config);
    }

    private void handleDatafeedSearch(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        writeJson(exchange, store.symbols(query.getOrDefault("query", "")).stream().map(symbol -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("symbol", symbol.get("symbol"));
            item.put("full_name", symbol.get("symbol"));
            item.put("description", symbol.get("description"));
            item.put("exchange", "KALSHI");
            item.put("ticker", symbol.get("symbol"));
            item.put("type", "prediction_market");
            return item;
        }).toList());
    }

    private void handleDatafeedSymbol(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        String symbol = required(query, "symbol");
        var metadata = store.symbol(symbol);
        if (metadata.isEmpty()) {
            writeJson(exchange, HttpURLConnection.HTTP_NOT_FOUND, Map.of("s", "error", "errmsg", "unknown symbol"));
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>(metadata.get());
        response.put("timezone", "Etc/UTC");
        response.put("session", "24x7");
        response.put("has_intraday", true);
        response.put("has_seconds", true);
        response.put("seconds_multipliers", List.of("1"));
        response.put("has_daily", true);
        response.put("volume_precision", 2);
        response.put("data_status", "streaming");
        writeJson(exchange, response);
    }

    private void handleDatafeedHistory(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        writeJson(exchange, store.tradingViewHistory(
            required(query, "symbol"),
            query.getOrDefault("resolution", "1"),
            optionalTime(query.get("from")),
            optionalTime(query.get("to")),
            intParam(query, "limit", 5000)
        ));
    }

    private void handleTime(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        write(exchange, "text/plain; charset=utf-8", Long.toString(System.currentTimeMillis() / 1000L));
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        writeJson(exchange, Map.of(
            "service", "kalshi-frontend-stream-adapter",
            "source_mode", "storage_backed_recorder",
            "endpoints", List.of(
                "/symbols",
                "/history?symbol=...&resolution=1&from=...&to=...",
                "/quotes?symbols=...",
                "/depth?symbol=...",
                "/trades?symbol=...",
                "/open-interest?symbol=...",
                "/stream",
                "/replay/sessions",
                "/datafeed/config"
            )
        ));
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("access-control-allow-origin", "*");
        exchange.getResponseHeaders().set("access-control-allow-methods", "GET,POST,OPTIONS");
        exchange.getResponseHeaders().set("access-control-allow-headers", "authorization,content-type");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_NO_CONTENT, -1);
            exchange.close();
            return false;
        }
        if (config.authToken().isBlank()) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (("Bearer " + config.authToken()).equals(authorization)) {
            return true;
        }
        writeJson(exchange, HttpURLConnection.HTTP_UNAUTHORIZED, Map.of("error", "unauthorized"));
        return false;
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(body);
    }

    private void writeJson(HttpExchange exchange, Object response) throws IOException {
        writeJson(exchange, HttpURLConnection.HTTP_OK, response);
    }

    private void writeJson(HttpExchange exchange, int status, Object response) throws IOException {
        write(exchange, status, "application/json; charset=utf-8", mapper.writeValueAsString(response));
    }

    private void write(HttpExchange exchange, String contentType, String body) throws IOException {
        write(exchange, HttpURLConnection.HTTP_OK, contentType, body);
    }

    private void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void metric(StringBuilder body, String name, Object value) {
        body.append(name).append(' ').append(value).append('\n');
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
        }
        return result;
    }

    private static String required(Map<String, String> query, String key) {
        String value = query.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required query parameter: " + key);
        }
        return value;
    }

    private static Long optionalTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long value = Long.parseLong(raw);
        return value < 10_000_000_000L ? value * 1000L : value;
    }

    private static int intParam(Map<String, String> query, String key, int defaultValue) {
        String value = query.get(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static List<String> csv(String raw) {
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
