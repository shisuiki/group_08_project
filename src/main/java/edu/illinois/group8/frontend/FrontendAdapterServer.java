package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.JdbcMarketMetadataReader;
import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.MarketMetadataReadRequest;
import edu.illinois.group8.storage.db.OperatorLatencyStatus;
import edu.illinois.group8.storage.db.OperatorPipelineStatus;
import edu.illinois.group8.storage.db.OperatorSemanticMetadataStatus;
import edu.illinois.group8.storage.db.SemanticMarketMetadataReadRequest;
import edu.illinois.group8.storage.db.SemanticMarketMetadataReader;
import edu.illinois.group8.storage.db.SemanticMarketMetadataRow;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

public class FrontendAdapterServer {
    private static final int DEFAULT_FEATURE_LIMIT = 100;
    private static final int MAX_FEATURE_LIMIT = 500;
    private static final int DEFAULT_MARKET_LIMIT = 100;
    private static final int MAX_MARKET_LIMIT = 500;
    private static final int DEFAULT_SEMANTIC_MARKET_LIMIT = 200;
    private static final int MAX_SEMANTIC_MARKET_LIMIT = 500;
    private static final List<String> SEMANTIC_METADATA_ENDPOINTS = List.of(
        "/api/semantic-metadata/markets",
        "/api/semantic-metadata/treemap"
    );
    private static final int HTTP_WORKER_THREADS = 8;
    private static final int QUOTE_UPDATE_MAX_WAITERS = 4;
    private static final int QUOTE_STREAM_MAX_STREAMS = 2;
    private static final long DEFAULT_QUOTE_UPDATE_TIMEOUT_MS = 15_000L;
    private static final long MAX_QUOTE_UPDATE_TIMEOUT_MS = 30_000L;
    private static final long QUOTE_STREAM_HEARTBEAT_MS = 1_000L;
    private static final long DATA_FRESHNESS_STALE_AFTER_MS = 15_000L;
    private static final Set<String> STATIC_ASSETS = Set.of(
        "index.html",
        "app.js",
        "styles.css",
        "vendor/lightweight-charts-4.2.0.standalone.production.js"
    );

    public record FeaturePlantStats(long eventsIn, long eventsOut, long errors) {
        public static final FeaturePlantStats EMPTY = new FeaturePlantStats(0L, 0L, 0L);
    }

    private final FrontendAdapterConfig config;
    private final FrontendFeatureStore store;
    private volatile FrontendMarketMetadataCatalog metadataCatalog;
    private volatile String lastCatalogMetadataRefreshKey = "";
    private final Supplier<FeaturePlantStats> featurePlantStats;
    private final Supplier<FeatureOutputRefreshStatus> featureOutputRefreshStatus;
    private final Supplier<OperatorPipelineStatus> operatorPipelineStatus;
    private final Function<String, OperatorLatencyStatus> operatorLatencyStatus;
    private final Supplier<OperatorSemanticMetadataStatus> operatorSemanticMetadataStatus;
    private final SemanticMarketMetadataReader semanticMarketMetadataReader;
    private final SemanticMetadataOperatorService semanticMetadataOperator;
    private final CatalogSyncOperatorService catalogSyncOperator;
    private final DemoOrchestratorService demoOrchestrator;
    private final FrontendReleaseInfo releaseInfo;
    private final OperatorControlPlane operatorControlPlane;
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();
    private final BackendMetrics metrics = new BackendMetrics();
    private final ConcurrentHashMap<String, LongAdder> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> requestDurationCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> requestDurationNanosSum = new ConcurrentHashMap<>();
    private final LongAdder quoteUpdateRequests = new LongAdder();
    private final LongAdder quoteUpdateChanged = new LongAdder();
    private final LongAdder quoteUpdateTimeouts = new LongAdder();
    private final LongAdder quoteUpdateClientDisconnects = new LongAdder();
    private final LongAdder quoteUpdateRejected = new LongAdder();
    private final AtomicLong quoteUpdateActiveWaits = new AtomicLong();
    private final Semaphore quoteUpdateWaitSlots = new Semaphore(QUOTE_UPDATE_MAX_WAITERS);
    private final LongAdder quoteStreamRequests = new LongAdder();
    private final LongAdder quoteStreamEvents = new LongAdder();
    private final LongAdder quoteStreamHeartbeats = new LongAdder();
    private final LongAdder quoteStreamClientDisconnects = new LongAdder();
    private final LongAdder quoteStreamRejected = new LongAdder();
    private final AtomicLong quoteStreamActiveStreams = new AtomicLong();
    private final Semaphore quoteStreamSlots = new Semaphore(QUOTE_STREAM_MAX_STREAMS);
    private final long startedAtMs = System.currentTimeMillis();
    private HttpServer httpServer;
    private ExecutorService httpExecutor;

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
        this(config, store, metadataCatalog, featurePlantStats, FeatureOutputRefreshStatus::disabled);
    }

    public FrontendAdapterServer(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        FrontendMarketMetadataCatalog metadataCatalog,
        Supplier<FeaturePlantStats> featurePlantStats,
        Supplier<FeatureOutputRefreshStatus> featureOutputRefreshStatus
    ) {
        this(
            config,
            store,
            metadataCatalog,
            featurePlantStats,
            featureOutputRefreshStatus,
            OperatorPipelineStatus::disabled,
            sourceEventId -> OperatorLatencyStatus.disabled(),
            () -> OperatorSemanticMetadataStatus.disabled("", "", ""),
            FrontendReleaseInfo.fromEnvironment()
        );
    }

    public FrontendAdapterServer(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        FrontendMarketMetadataCatalog metadataCatalog,
        Supplier<FeaturePlantStats> featurePlantStats,
        Supplier<FeatureOutputRefreshStatus> featureOutputRefreshStatus,
        Supplier<OperatorPipelineStatus> operatorPipelineStatus,
        FrontendReleaseInfo releaseInfo
    ) {
        this(
            config,
            store,
            metadataCatalog,
            featurePlantStats,
            featureOutputRefreshStatus,
            operatorPipelineStatus,
            sourceEventId -> OperatorLatencyStatus.disabled(),
            () -> OperatorSemanticMetadataStatus.disabled("", "", ""),
            releaseInfo
        );
    }

    public FrontendAdapterServer(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        FrontendMarketMetadataCatalog metadataCatalog,
        Supplier<FeaturePlantStats> featurePlantStats,
        Supplier<FeatureOutputRefreshStatus> featureOutputRefreshStatus,
        Supplier<OperatorPipelineStatus> operatorPipelineStatus,
        Function<String, OperatorLatencyStatus> operatorLatencyStatus,
        FrontendReleaseInfo releaseInfo
    ) {
        this(
            config,
            store,
            metadataCatalog,
            featurePlantStats,
            featureOutputRefreshStatus,
            operatorPipelineStatus,
            operatorLatencyStatus,
            () -> OperatorSemanticMetadataStatus.disabled("", "", ""),
            releaseInfo
        );
    }

    public FrontendAdapterServer(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        FrontendMarketMetadataCatalog metadataCatalog,
        Supplier<FeaturePlantStats> featurePlantStats,
        Supplier<FeatureOutputRefreshStatus> featureOutputRefreshStatus,
        Supplier<OperatorPipelineStatus> operatorPipelineStatus,
        Function<String, OperatorLatencyStatus> operatorLatencyStatus,
        Supplier<OperatorSemanticMetadataStatus> operatorSemanticMetadataStatus,
        FrontendReleaseInfo releaseInfo
    ) {
        this(
            config,
            store,
            metadataCatalog,
            featurePlantStats,
            featureOutputRefreshStatus,
            operatorPipelineStatus,
            operatorLatencyStatus,
            operatorSemanticMetadataStatus,
            request -> List.of(),
            releaseInfo
        );
    }

    public FrontendAdapterServer(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        FrontendMarketMetadataCatalog metadataCatalog,
        Supplier<FeaturePlantStats> featurePlantStats,
        Supplier<FeatureOutputRefreshStatus> featureOutputRefreshStatus,
        Supplier<OperatorPipelineStatus> operatorPipelineStatus,
        Function<String, OperatorLatencyStatus> operatorLatencyStatus,
        Supplier<OperatorSemanticMetadataStatus> operatorSemanticMetadataStatus,
        SemanticMarketMetadataReader semanticMarketMetadataReader,
        FrontendReleaseInfo releaseInfo
    ) {
        this(
            config,
            store,
            metadataCatalog,
            featurePlantStats,
            featureOutputRefreshStatus,
            operatorPipelineStatus,
            operatorLatencyStatus,
            operatorSemanticMetadataStatus,
            semanticMarketMetadataReader,
            releaseInfo,
            SemanticMetadataOperatorService.create(config, System.getenv())
        );
    }

    public FrontendAdapterServer(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        FrontendMarketMetadataCatalog metadataCatalog,
        Supplier<FeaturePlantStats> featurePlantStats,
        Supplier<FeatureOutputRefreshStatus> featureOutputRefreshStatus,
        Supplier<OperatorPipelineStatus> operatorPipelineStatus,
        Function<String, OperatorLatencyStatus> operatorLatencyStatus,
        Supplier<OperatorSemanticMetadataStatus> operatorSemanticMetadataStatus,
        SemanticMarketMetadataReader semanticMarketMetadataReader,
        FrontendReleaseInfo releaseInfo,
        SemanticMetadataOperatorService semanticMetadataOperator
    ) {
        this(
            config,
            store,
            metadataCatalog,
            featurePlantStats,
            featureOutputRefreshStatus,
            operatorPipelineStatus,
            operatorLatencyStatus,
            operatorSemanticMetadataStatus,
            semanticMarketMetadataReader,
            releaseInfo,
            semanticMetadataOperator,
            CatalogSyncOperatorService.create(config, System.getenv())
        );
    }

    public FrontendAdapterServer(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        FrontendMarketMetadataCatalog metadataCatalog,
        Supplier<FeaturePlantStats> featurePlantStats,
        Supplier<FeatureOutputRefreshStatus> featureOutputRefreshStatus,
        Supplier<OperatorPipelineStatus> operatorPipelineStatus,
        Function<String, OperatorLatencyStatus> operatorLatencyStatus,
        Supplier<OperatorSemanticMetadataStatus> operatorSemanticMetadataStatus,
        SemanticMarketMetadataReader semanticMarketMetadataReader,
        FrontendReleaseInfo releaseInfo,
        SemanticMetadataOperatorService semanticMetadataOperator,
        CatalogSyncOperatorService catalogSyncOperator
    ) {
        this.config = config;
        this.store = store;
        this.metadataCatalog = metadataCatalog == null
            ? FrontendMarketMetadataCatalog.disabled("disabled")
            : metadataCatalog;
        this.featurePlantStats = featurePlantStats == null ? () -> FeaturePlantStats.EMPTY : featurePlantStats;
        this.featureOutputRefreshStatus = featureOutputRefreshStatus == null
            ? FeatureOutputRefreshStatus::disabled
            : featureOutputRefreshStatus;
        this.operatorPipelineStatus = operatorPipelineStatus == null
            ? OperatorPipelineStatus::disabled
            : operatorPipelineStatus;
        this.operatorLatencyStatus = operatorLatencyStatus == null
            ? sourceEventId -> OperatorLatencyStatus.disabled()
            : operatorLatencyStatus;
        this.operatorSemanticMetadataStatus = operatorSemanticMetadataStatus == null
            ? () -> OperatorSemanticMetadataStatus.disabled("", "", "")
            : operatorSemanticMetadataStatus;
        this.semanticMarketMetadataReader = semanticMarketMetadataReader == null
            ? request -> List.of()
            : semanticMarketMetadataReader;
        this.semanticMetadataOperator = semanticMetadataOperator == null
            ? SemanticMetadataOperatorService.create(config, System.getenv())
            : semanticMetadataOperator;
        this.catalogSyncOperator = catalogSyncOperator == null
            ? CatalogSyncOperatorService.create(config, System.getenv())
            : catalogSyncOperator;
        this.releaseInfo = releaseInfo == null ? FrontendReleaseInfo.empty() : releaseInfo;
        this.operatorControlPlane = new OperatorControlPlane(this.config, this.releaseInfo);
        this.demoOrchestrator = DemoOrchestratorService.create(
            this.config,
            this.releaseInfo,
            this.catalogSyncOperator,
            this.semanticMetadataOperator,
            this::demoProductStatusSnapshot
        );
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        bind("/datafeed/config", this::handleDatafeedConfig);
        bind("/datafeed/symbols", this::handleDatafeedSymbols);
        bind("/datafeed/search", this::handleDatafeedSearch);
        bind("/datafeed/history", this::handleDatafeedHistory);
        bind("/datafeed/time", this::handleDatafeedTime);
        bind("/symbols", this::handleSymbols);
        bind("/quotes/stream", this::handleQuoteStream);
        bind("/quotes/updates", this::handleQuoteUpdates);
        bind("/quotes", this::handleQuotes);
        bind("/features", this::handleFeatures);
        bind("/markets", this::handleMarkets);
        bind("/api/semantic-metadata/markets", this::handleSemanticMetadataMarkets);
        bind("/api/semantic-metadata/treemap", this::handleSemanticMetadataTreemap);
        bind("/health", this::handleHealth);
        bind("/metrics", this::handleMetrics);
        bind("/ops/pipeline", this::handleOpsPipeline);
        bind("/ops/latency", this::handleOpsLatency);
        bind("/operator/status", this::handleOperatorStatus);
        bind("/operator/plan", this::handleOperatorPlan);
        bind("/operator/catalog/sync", this::handleOperatorCatalogSync);
        bind("/operator/catalog/sync-status", this::handleOperatorCatalogSyncStatus);
        bind("/operator/semantic-metadata/run", this::handleOperatorSemanticMetadataRun);
        bind("/operator/semantic-metadata/run-status", this::handleOperatorSemanticMetadataRunStatus);
        bind("/operator/demo-orchestrator/run", this::handleOperatorDemoOrchestratorRun);
        bind("/operator/demo-orchestrator/run-status", this::handleOperatorDemoOrchestratorRunStatus);
        bind("/", this::handleStaticAsset);
        httpExecutor = Executors.newFixedThreadPool(
            HTTP_WORKER_THREADS,
            daemonThreadFactory("frontend-adapter-http")
        );
        httpServer.setExecutor(httpExecutor);
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
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        semanticMetadataOperator.close();
        catalogSyncOperator.close();
        demoOrchestrator.close();
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
            if (!isAuthorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"kalshi-product\"");
                writeError(exchange, 401, "unauthorized");
                return;
            }
            delegate.handle(exchange);
        } catch (RuntimeException e) {
            writeError(exchange, 500, e.getMessage());
        } finally {
            sum.addAndGet(System.nanoTime() - startNs);
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        if (!config.basicAuthEnabled()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int separator = decoded.indexOf(':');
        if (separator < 0) {
            return false;
        }
        String user = decoded.substring(0, separator);
        String password = decoded.substring(separator + 1);
        return constantTimeEquals(user, config.basicAuthUser())
            && constantTimeEquals(password, config.basicAuthPassword());
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        return MessageDigest.isEqual(
            actual.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8)
        );
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
        boolean includeSmoke = includeSmokeMarkets(params);
        for (MarketMetadata metadata : metadataCatalog.search(query, null, limit, includeSmoke)) {
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
            if (!includeSmoke && FrontendSyntheticData.isSmokeMarketTicker(symbol)) {
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
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        boolean includeSmoke = includeSmokeMarkets(params);
        Set<String> symbols = store.symbols();
        List<Map<String, Object>> entries = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            if (!includeSmoke && FrontendSyntheticData.isSmokeMarketTicker(symbol)) {
                continue;
            }
            Optional<FeatureOutput> latestBbo = store.latest(symbol, FrontendFeatureStore.BBO_FEATURE);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("symbol", symbol);
            entry.put("latest_event_ts_ms", latestBbo.map(FeatureOutput::eventTsMs).orElse(null));
            String sourceKind = latestBbo.map(FrontendSyntheticData::sourceKind)
                .orElse(FrontendSyntheticData.isSmokeMarketTicker(symbol)
                    ? FrontendSyntheticData.SOURCE_KIND_SMOKE
                    : FrontendSyntheticData.SOURCE_KIND_UNKNOWN);
            entry.put("source_kind", sourceKind);
            entry.put("synthetic", FrontendSyntheticData.isSynthetic(sourceKind));
            entries.add(entry);
        }
        writeJson(exchange, 200, Map.of("symbols", entries));
    }

    private void handleQuotes(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        List<String> symbols = parseSymbols(params.getOrDefault("symbols", ""));
        writeJson(exchange, 200, quotesBody(symbols, store.sequence()));
    }

    private void handleQuoteStream(HttpExchange exchange) throws IOException {
        quoteStreamRequests.increment();
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        List<String> symbols = parseSymbols(params.getOrDefault("symbols", ""));
        if (!quoteStreamSlots.tryAcquire()) {
            quoteStreamRejected.increment();
            writeError(exchange, 429, "too many active quote streams");
            return;
        }
        quoteStreamActiveStreams.incrementAndGet();
        try {
            exchange.getResponseHeaders().set("content-type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("cache-control", "no-cache");
            exchange.getResponseHeaders().set("x-accel-buffering", "no");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                long sequence = store.sequence();
                writeQuoteStreamEvent(out, symbols, sequence, false);
                while (!Thread.currentThread().isInterrupted()) {
                    long nextSequence;
                    try {
                        nextSequence = store.waitForSequenceAfter(sequence, QUOTE_STREAM_HEARTBEAT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (nextSequence > sequence) {
                        sequence = nextSequence;
                        writeQuoteStreamEvent(out, symbols, sequence, true);
                    } else {
                        writeQuoteStreamHeartbeat(out);
                    }
                }
            }
        } catch (IOException e) {
            quoteStreamClientDisconnects.increment();
        } finally {
            quoteStreamActiveStreams.decrementAndGet();
            quoteStreamSlots.release();
        }
    }

    private void handleQuoteUpdates(HttpExchange exchange) throws IOException {
        quoteUpdateRequests.increment();
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        List<String> symbols = parseSymbols(params.getOrDefault("symbols", ""));
        long timeoutMs;
        long after;
        boolean afterProvided = params.containsKey("after") && !params.getOrDefault("after", "").isBlank();
        try {
            timeoutMs = parseQuoteUpdateTimeoutMs(params.get("timeout_ms"));
            after = afterProvided
                ? parseNonNegativeLong(params.get("after"), "after")
                : store.sequence();
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
            return;
        }

        long startedNs = System.nanoTime();
        long sequence = store.sequence();
        boolean changed = sequence > after;
        if (afterProvided && !changed) {
            if (!quoteUpdateWaitSlots.tryAcquire()) {
                quoteUpdateRejected.increment();
                writeError(exchange, 429, "too many pending quote update requests");
                return;
            }
            quoteUpdateActiveWaits.incrementAndGet();
            try {
                sequence = store.waitForSequenceAfter(after, timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                writeError(exchange, 503, "interrupted while waiting for quote updates");
                return;
            } finally {
                quoteUpdateActiveWaits.decrementAndGet();
                quoteUpdateWaitSlots.release();
            }
            changed = sequence > after;
        }
        if (changed) {
            quoteUpdateChanged.increment();
        } else if (afterProvided) {
            quoteUpdateTimeouts.increment();
        }

        Map<String, Object> body = quotesBody(symbols, sequence);
        body.put("changed", changed);
        body.put("after", after);
        body.put("timeout_ms", timeoutMs);
        body.put("wait_ms", (System.nanoTime() - startedNs) / 1_000_000L);
        body.put("server_ts_ms", System.currentTimeMillis());
        try {
            writeJson(exchange, 200, body);
        } catch (IOException e) {
            quoteUpdateClientDisconnects.increment();
            throw e;
        }
    }

    private Map<String, Object> quotesBody(List<String> symbols, long sequence) {
        List<Map<String, Object>> quotes = new ArrayList<>();
        for (String symbol : symbols) {
            Optional<FeatureOutput> latest = store.latest(symbol, FrontendFeatureStore.BBO_FEATURE);
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("symbol", symbol);
            if (latest.isPresent()) {
                FeatureOutput out = latest.get();
                quote.put("bid_micros", out.values().get("bid_price_micros"));
                quote.put("ask_micros", out.values().get("ask_price_micros"));
                quote.put("midpoint_micros", out.values().get("midpoint_micros"));
                quote.put("event_ts_ms", out.eventTsMs());
                quote.put("source_event_id", out.sourceEventId());
                quote.put("feature_name", out.featureName());
                String sourceKind = FrontendSyntheticData.sourceKind(out);
                quote.put("source_kind", sourceKind);
                quote.put("synthetic", FrontendSyntheticData.isSynthetic(sourceKind));
            } else {
                quote.put("bid_micros", null);
                quote.put("ask_micros", null);
                quote.put("midpoint_micros", null);
                quote.put("event_ts_ms", null);
                quote.put("source_event_id", null);
                quote.put("feature_name", null);
                quote.put("source_kind", FrontendSyntheticData.SOURCE_KIND_UNKNOWN);
                quote.put("synthetic", false);
            }
            quotes.add(quote);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sequence", sequence);
        body.put("server_ts_ms", System.currentTimeMillis());
        body.put("quotes", quotes);
        return body;
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

        Long fromMs;
        Long toMs;
        try {
            fromMs = parseOptionalMillis(params.get("from_ms"), "from_ms");
            toMs = parseOptionalMillis(params.get("to_ms"), "to_ms");
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
            return;
        }

        List<FeatureOutput> snapshot = store.snapshot(symbol, feature).stream()
            .filter(output -> fromMs == null || (output.eventTsMs() != null && output.eventTsMs() >= fromMs))
            .filter(output -> toMs == null || (output.eventTsMs() != null && output.eventTsMs() <= toMs))
            .toList();
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
        boolean includeSmoke = includeSmokeMarkets(params);
        List<Map<String, Object>> markets = metadataCatalog.search(query, status, limit, includeSmoke).stream()
            .map(FrontendAdapterServer::marketMetadataBody)
            .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", markets.size());
        body.put("markets", markets);
        writeJson(exchange, 200, body);
    }

    private void handleSemanticMetadataMarkets(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        SemanticMarketMetadataReadRequest request;
        try {
            request = semanticMetadataReadRequest(params);
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
            return;
        }
        if (semanticMetadataReadDisabled()) {
            writeJson(exchange, 200, semanticMetadataUnavailableBody("disabled", request, List.of(), null));
            return;
        }
        try {
            List<Map<String, Object>> markets = semanticMarketMetadataReader.read(request).stream()
                .map(FrontendAdapterServer::semanticMarketBody)
                .toList();
            Map<String, Object> body = semanticMetadataUnavailableBody("ok", request, markets, null);
            body.put("markets", markets);
            writeJson(exchange, 200, body);
        } catch (RuntimeException e) {
            writeJson(exchange, 200, semanticMetadataUnavailableBody("unavailable", request, List.of(), e.getMessage()));
        }
    }

    private void handleSemanticMetadataTreemap(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        SemanticMarketMetadataReadRequest request;
        String groupBy;
        try {
            request = semanticMetadataReadRequest(params);
            groupBy = semanticTreemapGroupBy(params.get("group_by"));
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
            return;
        }
        if (semanticMetadataReadDisabled()) {
            writeJson(exchange, 200, semanticTreemapBody("disabled", request, groupBy, List.of(), null));
            return;
        }
        try {
            List<SemanticMarketMetadataRow> rows = semanticMarketMetadataReader.read(request);
            writeJson(exchange, 200, semanticTreemapBody("ok", request, groupBy, rows, null));
        } catch (RuntimeException e) {
            writeJson(exchange, 200, semanticTreemapBody("unavailable", request, groupBy, List.of(), e.getMessage()));
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        FeaturePlantStats stats = featurePlantStats.get();
        long nowMs = System.currentTimeMillis();
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "ok");
        health.put("service", "frontend-adapter");
        health.put("release", releaseInfo.toBody());
        health.put("source_mode", config.sourceMode().name().toLowerCase(Locale.ROOT));
        health.put("feature_source", config.featureSource().name().toLowerCase(Locale.ROOT));
        health.put("started_at", java.time.Instant.ofEpochMilli(startedAtMs).toString());
        health.put("uptime_ms", nowMs - startedAtMs);
        Map<String, Object> storeView = new LinkedHashMap<>();
        storeView.put("symbols", store.symbolCount());
        storeView.put("total_features", store.totalAccepted());
        storeView.put("sequence", store.sequence());
        health.put("store", storeView);
        FrontendFeatureStore.DataFreshness freshness = store.latestFreshness(nowMs);
        FeatureOutputRefreshStatus refreshStatus = featureOutputRefreshStatus.get();
        health.put("data_freshness", dataFreshnessBody(freshness));
        health.put("product_readiness", productReadinessBody(freshness, refreshStatus));
        health.put("quote_updates", quoteUpdatesBody());
        health.put("quote_streams", quoteStreamsBody());
        Map<String, Object> metadataView = new LinkedHashMap<>();
        metadataView.put("source", metadataCatalog.source());
        metadataView.put("status", metadataCatalog.loadStatus().name().toLowerCase(Locale.ROOT));
        metadataView.put("markets", metadataCatalog.size());
        if (metadataCatalog.error() != null) {
            metadataView.put("error", operatorVisibleError(metadataCatalog.error()));
        }
        health.put("market_metadata", metadataView);
        health.put("catalog_sync", catalogSyncOperator.statusBody());
        health.put("demo_orchestrator", demoOrchestrator.statusBody());
        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("events_in", stats.eventsIn());
        fp.put("events_out", stats.eventsOut());
        fp.put("errors", stats.errors());
        health.put("feature_plant", fp);
        health.put("feature_output_refresh", featureOutputRefreshBody(refreshStatus));
        health.put("operator_pipeline", operatorPipelineStatusBody(operatorPipelineStatus.get()));
        health.put("semantic_metadata", semanticMetadataStatusBody(operatorSemanticMetadataStatus.get()));
        writeJson(exchange, 200, health);
    }

    private void handleOpsPipeline(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        OperatorPipelineStatus pipeline = operatorPipelineStatus.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", pipeline == null ? "disabled" : pipeline.status());
        body.put("generated_at", java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        body.put("pipeline", operatorPipelineStatusBody(pipeline));
        writeJson(exchange, 200, body);
    }

    private void handleOpsLatency(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String sourceEventId = params.getOrDefault("source_event_id", "").trim();
        if (sourceEventId.isBlank()) {
            sourceEventId = store.latestFreshness(System.currentTimeMillis()).sourceEventId();
        }
        OperatorLatencyStatus latency;
        if (sourceEventId == null || sourceEventId.isBlank()) {
            latency = OperatorLatencyStatus.missing("", "missing_source_event_id");
        } else {
            try {
                latency = operatorLatencyStatus.apply(sourceEventId);
            } catch (IllegalArgumentException e) {
                latency = OperatorLatencyStatus.missing(sourceEventId, e.getMessage());
            } catch (RuntimeException e) {
                latency = OperatorLatencyStatus.unavailable(sourceEventId, e.getMessage());
            }
        }
        Map<String, Object> body = operatorLatencyStatusBody(latency);
        body.put("generated_at", java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        writeJson(exchange, 200, body);
    }

    private void handleOperatorStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        long nowMs = System.currentTimeMillis();
        FeatureOutputRefreshStatus refreshStatus = featureOutputRefreshStatus.get();
        FrontendFeatureStore.DataFreshness freshness = store.latestFreshness(nowMs);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("service", "frontend-adapter");
        body.put("generated_at", java.time.Instant.ofEpochMilli(nowMs).toString());
        body.put("operator_control", operatorControlPlane.controlStatus());
        body.put("release", releaseInfo.toBody());
        body.put("runtime", runtimeStatusBody());
        body.put("configuration", operatorControlPlane.configurationStatus());
        body.put("pipeline", operatorPipelineStatusBody(operatorPipelineStatus.get()));
        body.put("catalog_sync", catalogSyncOperator.statusBody());
        body.put("catalog_sync_run", catalogSyncOperator.statusBody());
        body.put("semantic_metadata", semanticMetadataStatusBody(operatorSemanticMetadataStatus.get()));
        body.put("semantic_metadata_run", semanticMetadataOperator.statusBody());
        body.put("demo_orchestrator", demoOrchestrator.statusBody());
        body.put("data_freshness", dataFreshnessBody(freshness));
        body.put("product_readiness", productReadinessBody(freshness, refreshStatus));
        body.put("feature_output_refresh", featureOutputRefreshBody(refreshStatus));
        body.put("quote_updates", quoteUpdatesBody());
        body.put("quote_streams", quoteStreamsBody());
        writeJson(exchange, 200, body);
    }

    private void handleOperatorPlan(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        if (!config.operatorControlEnabled()) {
            writeError(exchange, 403, "operator control POST is disabled");
            return;
        }
        if (!config.basicAuthEnabled()) {
            writeError(exchange, 403, "operator control POST requires Basic Auth");
            return;
        }
        try {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            writeJson(exchange, 200, operatorControlPlane.plan(request));
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
        } catch (IOException e) {
            writeError(exchange, 400, "malformed JSON payload");
        }
    }

    private void handleOperatorSemanticMetadataRun(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        if (!config.operatorControlEnabled()) {
            writeError(exchange, 403, "operator control POST is disabled");
            return;
        }
        if (!config.basicAuthEnabled()) {
            writeError(exchange, 403, "operator control POST requires Basic Auth");
            return;
        }
        try {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            writeJson(exchange, 202, semanticMetadataOperator.start(request));
        } catch (SemanticMetadataOperatorService.RunAlreadyActiveException e) {
            Map<String, Object> body = new LinkedHashMap<>(semanticMetadataOperator.statusBody());
            body.put("status", "blocked");
            body.put("message", e.getMessage());
            writeJson(exchange, 409, body);
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
        } catch (IOException e) {
            writeError(exchange, 400, "malformed JSON payload");
        }
    }

    private void handleOperatorCatalogSync(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        if (!config.operatorControlEnabled()) {
            writeError(exchange, 403, "operator control POST is disabled");
            return;
        }
        if (!config.basicAuthEnabled()) {
            writeError(exchange, 403, "operator control POST requires Basic Auth");
            return;
        }
        try {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            writeJson(exchange, 202, catalogSyncOperator.start(request));
        } catch (CatalogSyncOperatorService.RunAlreadyActiveException e) {
            Map<String, Object> body = new LinkedHashMap<>(catalogSyncOperator.statusBody());
            body.put("status", "blocked");
            body.put("message", e.getMessage());
            writeJson(exchange, 409, body);
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
        } catch (IOException e) {
            writeError(exchange, 400, "malformed JSON payload");
        }
    }

    private void handleOperatorCatalogSyncStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        Map<String, Object> body = catalogSyncOperator.statusBody();
        refreshMetadataCatalogAfterCatalogSync(body);
        writeJson(exchange, 200, body);
    }

    private void handleOperatorSemanticMetadataRunStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        writeJson(exchange, 200, semanticMetadataOperator.statusBody());
    }

    private void handleOperatorDemoOrchestratorRun(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        if (!config.operatorControlEnabled()) {
            writeError(exchange, 403, "operator control POST is disabled");
            return;
        }
        if (!config.basicAuthEnabled()) {
            writeError(exchange, 403, "operator control POST requires Basic Auth");
            return;
        }
        try {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            writeJson(exchange, 202, demoOrchestrator.start(request));
        } catch (DemoOrchestratorService.RunAlreadyActiveException e) {
            Map<String, Object> body = new LinkedHashMap<>(demoOrchestrator.statusBody());
            body.put("status", "blocked");
            body.put("message", e.getMessage());
            writeJson(exchange, 409, body);
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
        } catch (IOException e) {
            writeError(exchange, 400, "malformed JSON payload");
        }
    }

    private void handleOperatorDemoOrchestratorRunStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        writeJson(exchange, 200, demoOrchestrator.statusBody());
    }

    private Map<String, Object> demoProductStatusSnapshot() {
        long nowMs = System.currentTimeMillis();
        FeatureOutputRefreshStatus refreshStatus = featureOutputRefreshStatus.get();
        FrontendFeatureStore.DataFreshness freshness = store.latestFreshness(nowMs);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("release", releaseInfo.toBody());
        body.put("runtime", runtimeStatusBody());
        body.put("store", Map.of(
            "symbols", store.symbolCount(),
            "total_features", store.totalAccepted(),
            "sequence", store.sequence()
        ));
        body.put("data_freshness", dataFreshnessBody(freshness));
        body.put("product_readiness", productReadinessBody(freshness, refreshStatus));
        body.put("feature_output_refresh", featureOutputRefreshBody(refreshStatus));
        body.put("quote_updates", quoteUpdatesBody());
        body.put("quote_streams", quoteStreamsBody());
        body.put("pipeline", operatorPipelineStatusBody(operatorPipelineStatus.get()));
        body.put("semantic_metadata", semanticMetadataStatusBody(operatorSemanticMetadataStatus.get()));
        body.put("catalog_sync", catalogSyncOperator.statusBody());
        body.put("semantic_metadata_run", semanticMetadataOperator.statusBody());
        body.put("market_metadata", Map.of(
            "source", metadataCatalog.source(),
            "status", metadataCatalog.loadStatus().name().toLowerCase(Locale.ROOT),
            "markets", metadataCatalog.size()
        ));
        return body;
    }

    private void refreshMetadataCatalogAfterCatalogSync(Map<String, Object> body) {
        if (config.dbUrl().isBlank()) {
            return;
        }
        Object latest = body == null ? null : body.get("latest_run");
        if (!(latest instanceof Map<?, ?> latestRun) || !"completed".equals(String.valueOf(latestRun.get("state")))) {
            return;
        }
        String key = String.valueOf(latestRun.get("run_id")) + ":" + String.valueOf(latestRun.get("finished_at"));
        if (key.equals(lastCatalogMetadataRefreshKey)) {
            return;
        }
        try {
            JdbcMarketMetadataReader reader =
                JdbcMarketMetadataReader.fromDriverManager(config.dbUrl(), config.dbUser(), config.dbPassword());
            List<MarketMetadata> rows =
                reader.read(MarketMetadataReadRequest.search(null, null, config.metadataMaxRows()));
            metadataCatalog = FrontendMarketMetadataCatalog.loaded("db", rows);
            lastCatalogMetadataRefreshKey = key;
        } catch (RuntimeException e) {
            metadataCatalog = FrontendMarketMetadataCatalog.unavailable("db", e.getMessage());
        }
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        metrics.setGauge("frontend_adapter_symbols", store.symbolCount());
        metrics.setGauge("frontend_adapter_features_total", store.totalAccepted());
        metrics.setGauge("frontend_adapter_store_sequence", store.sequence());
        StringBuilder body = new StringBuilder();
        body.append(metrics.prometheusText());
        body.append("frontend_adapter_quote_update_requests_total ")
            .append(quoteUpdateRequests.sum())
            .append('\n');
        body.append("frontend_adapter_quote_update_changed_total ")
            .append(quoteUpdateChanged.sum())
            .append('\n');
        body.append("frontend_adapter_quote_update_timeouts_total ")
            .append(quoteUpdateTimeouts.sum())
            .append('\n');
        body.append("frontend_adapter_quote_update_client_disconnects_total ")
            .append(quoteUpdateClientDisconnects.sum())
            .append('\n');
        body.append("frontend_adapter_quote_update_rejected_total ")
            .append(quoteUpdateRejected.sum())
            .append('\n');
        body.append("frontend_adapter_quote_update_active ")
            .append(quoteUpdateActiveWaits.get())
            .append('\n');
        body.append("frontend_adapter_quote_stream_requests_total ")
            .append(quoteStreamRequests.sum())
            .append('\n');
        body.append("frontend_adapter_quote_stream_events_total ")
            .append(quoteStreamEvents.sum())
            .append('\n');
        body.append("frontend_adapter_quote_stream_heartbeats_total ")
            .append(quoteStreamHeartbeats.sum())
            .append('\n');
        body.append("frontend_adapter_quote_stream_client_disconnects_total ")
            .append(quoteStreamClientDisconnects.sum())
            .append('\n');
        body.append("frontend_adapter_quote_stream_rejected_total ")
            .append(quoteStreamRejected.sum())
            .append('\n');
        body.append("frontend_adapter_quote_stream_active ")
            .append(quoteStreamActiveStreams.get())
            .append('\n');
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

    private void handleStaticAsset(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getRawPath();
        String path;
        try {
            path = rawPath == null || rawPath.isBlank()
                ? "/"
                : URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            writeError(exchange, 404, "static asset not found");
            return;
        }

        String assetName;
        if ("/".equals(path) || "/index.html".equals(path)) {
            assetName = "index.html";
        } else if (path.startsWith("/")) {
            assetName = path.substring(1);
        } else {
            assetName = path;
        }
        if (!STATIC_ASSETS.contains(assetName)) {
            writeError(exchange, 404, "static asset not found");
            return;
        }

        Path root = config.staticRoot().toAbsolutePath().normalize();
        Path file = root.resolve(assetName).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            writeError(exchange, 404, "static asset not found: " + assetName);
            return;
        }
        writeBytes(exchange, 200, staticContentType(assetName), Files.readAllBytes(file));
    }

    private static Map<String, Object> featureOutputBody(FeatureOutput output) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("feature_name", output.featureName());
        body.put("market_ticker", output.marketTicker());
        body.put("event_ts_ms", output.eventTsMs());
        body.put("source_event_id", output.sourceEventId());
        String sourceKind = FrontendSyntheticData.sourceKind(output);
        body.put("source_kind", sourceKind);
        body.put("synthetic", FrontendSyntheticData.isSynthetic(sourceKind));
        body.put("values", output.values());
        return body;
    }

    private static Map<String, Object> featureOutputRefreshBody(FeatureOutputRefreshStatus status) {
        FeatureOutputRefreshStatus view = status == null ? FeatureOutputRefreshStatus.disabled() : status;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", view.enabled());
        body.put("running", view.running());
        body.put("last_success_at", view.lastSuccessAt() == null ? null : view.lastSuccessAt().toString());
        body.put("last_error_at", view.lastErrorAt() == null ? null : view.lastErrorAt().toString());
        body.put("last_error", operatorVisibleError(view.lastError()));
        body.put("last_row_count", view.lastRowCount());
        body.put("total_loaded", view.totalLoaded());
        body.put("refresh_errors", view.refreshErrors());
        return body;
    }

    private Map<String, Object> runtimeStatusBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_mode", config.sourceMode().name().toLowerCase(Locale.ROOT));
        body.put("feature_source", config.featureSource().name().toLowerCase(Locale.ROOT));
        body.put("metadata_source", config.metadataSource().name().toLowerCase(Locale.ROOT));
        body.put("feature_output_refresh_enabled", config.featureOutputRefreshEnabled());
        body.put("db_include_replay", config.dbIncludeReplayEvents());
        body.put("db_replay_id_configured", !config.dbReplayId().isBlank());
        body.put("basic_auth_enabled", config.basicAuthEnabled());
        body.put("started_at", java.time.Instant.ofEpochMilli(startedAtMs).toString());
        body.put("uptime_ms", System.currentTimeMillis() - startedAtMs);
        return body;
    }

    private Map<String, Object> quoteUpdatesBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requests", quoteUpdateRequests.sum());
        body.put("changed", quoteUpdateChanged.sum());
        body.put("timeouts", quoteUpdateTimeouts.sum());
        body.put("client_disconnects", quoteUpdateClientDisconnects.sum());
        body.put("rejected", quoteUpdateRejected.sum());
        body.put("active_waits", quoteUpdateActiveWaits.get());
        body.put("max_waits", QUOTE_UPDATE_MAX_WAITERS);
        return body;
    }

    private Map<String, Object> quoteStreamsBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requests", quoteStreamRequests.sum());
        body.put("events", quoteStreamEvents.sum());
        body.put("heartbeats", quoteStreamHeartbeats.sum());
        body.put("client_disconnects", quoteStreamClientDisconnects.sum());
        body.put("rejected", quoteStreamRejected.sum());
        body.put("active_streams", quoteStreamActiveStreams.get());
        body.put("max_streams", QUOTE_STREAM_MAX_STREAMS);
        return body;
    }

    private static Map<String, Object> operatorPipelineStatusBody(OperatorPipelineStatus status) {
        OperatorPipelineStatus view = status == null ? OperatorPipelineStatus.disabled() : status;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", view.status());
        body.put("cursor_name", view.cursorName());
        body.put("cursor_commit_seq", view.cursorCommitSeq());
        body.put("canonical_max_commit_seq", view.canonicalMaxCommitSeq());
        body.put("cursor_lag_events", view.cursorLagEvents());
        body.put("latest_market_state_commit_seq", view.latestMarketStateCommitSeq());
        body.put("latest_state_age_ms", view.latestStateAgeMs());
        body.put("recent_canonical_events", view.recentCanonicalEvents());
        body.put("recent_feature_outputs", view.recentFeatureOutputs());
        body.put("recent_latest_market_states", view.recentLatestMarketStates());
        body.put("recent_window_seconds", view.recentWindowSeconds());
        if (view.error() != null) {
            body.put("error", operatorVisibleError(view.error()));
        }
        return body;
    }

    private static Map<String, Object> operatorLatencyStatusBody(OperatorLatencyStatus status) {
        OperatorLatencyStatus view = status == null ? OperatorLatencyStatus.disabled() : status;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", view.status());
        body.put("source_event_id", view.sourceEventId());
        body.put("market_ticker", view.marketTicker());
        body.put("canonical_commit_seq", view.canonicalCommitSeq());
        body.put("latest_market_state_commit_seq", view.latestMarketStateCommitSeq());
        body.put("canonical_to_feature_ms", view.canonicalToFeatureMs());
        body.put("feature_to_latest_state_ms", view.featureToLatestStateMs());
        body.put("canonical_to_latest_state_ms", view.canonicalToLatestStateMs());
        body.put("reason", view.reason());
        if (view.error() != null) {
            body.put("error", operatorVisibleError(view.error()));
        }
        return body;
    }

    private SemanticMarketMetadataReadRequest semanticMetadataReadRequest(Map<String, String> params) {
        int limit = parseLimit(params.get("limit"), DEFAULT_SEMANTIC_MARKET_LIMIT, MAX_SEMANTIC_MARKET_LIMIT);
        String taxonomyVersion = firstNonBlank(
            params.get("taxonomy_version"),
            config.llmMetadataTaxonomyVersion(),
            "v1"
        );
        String rawStatus = normalize(params.get("status"));
        String semanticStatus = firstNonBlankOrNull(
            params.get("semantic_status"),
            truthy(params.get("generated")) ? "generated" : null,
            isSemanticMetadataStatus(rawStatus) ? rawStatus : null
        );
        String marketStatus = firstNonBlankOrNull(
            params.get("market_status"),
            rawStatus != null && !isSemanticMetadataStatus(rawStatus) ? rawStatus : null
        );
        return new SemanticMarketMetadataReadRequest(
            taxonomyVersion,
            firstNonBlankOrNull(params.get("market_ticker"), params.get("ticker"), params.get("symbol")),
            semanticStatus,
            marketStatus,
            normalize(params.get("tag")),
            firstNonBlankOrNull(params.get("q"), params.get("search"), params.get("query")),
            limit
        );
    }

    private boolean semanticMetadataReadDisabled() {
        return config.semanticMetadataStatusSource() == FrontendAdapterConfig.SemanticMetadataStatusSource.DISABLED
            || config.dbUrl().isBlank();
    }

    private static Map<String, Object> semanticMetadataUnavailableBody(
        String status,
        SemanticMarketMetadataReadRequest request,
        List<Map<String, Object>> markets,
        String error
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("taxonomy_version", request.taxonomyVersion());
        body.put("limit", request.maxRows());
        body.put("count", markets.size());
        body.put("endpoints", SEMANTIC_METADATA_ENDPOINTS);
        body.put("markets", markets);
        if (error != null) {
            body.put("error", operatorVisibleError(error));
        }
        return body;
    }

    private static Map<String, Object> semanticTreemapBody(
        String status,
        SemanticMarketMetadataReadRequest request,
        String groupBy,
        List<SemanticMarketMetadataRow> rows,
        String error
    ) {
        Map<String, SemanticTreemapGroup> groups = new LinkedHashMap<>();
        for (SemanticMarketMetadataRow row : rows) {
            for (String key : semanticGroupKeys(row, groupBy)) {
                groups.computeIfAbsent(key, value -> new SemanticTreemapGroup(groupBy, value)).add(row);
            }
        }
        List<Map<String, Object>> groupBodies = groups.values().stream()
            .map(SemanticTreemapGroup::body)
            .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("taxonomy_version", request.taxonomyVersion());
        body.put("group_by", groupBy);
        body.put("limit", request.maxRows());
        body.put("count", rows.size());
        body.put("endpoints", SEMANTIC_METADATA_ENDPOINTS);
        body.put("groups", groupBodies);
        if (error != null) {
            body.put("error", operatorVisibleError(error));
        }
        return body;
    }

    private static Map<String, Object> semanticMarketBody(SemanticMarketMetadataRow row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("market_ticker", row.marketTicker());
        body.put("event_ticker", row.eventTicker());
        body.put("series_ticker", row.seriesTicker());
        body.put("status", row.marketStatus());
        body.put("title", row.marketTitle());
        body.put("semantic_metadata", semanticDetailsBody(row));
        body.put("quote", semanticQuoteBody(row));
        return body;
    }

    private static Map<String, Object> semanticDetailsBody(SemanticMarketMetadataRow row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taxonomy_version", row.taxonomyVersion());
        body.put("model", row.model());
        body.put("prompt_version", row.promptVersion());
        body.put("status", row.semanticStatus());
        body.put("sector", row.sector());
        body.put("subsector", row.subsector());
        body.put("event_type", row.eventType());
        body.put("region", row.region());
        body.put("time_horizon", row.timeHorizon());
        body.put("liquidity_bucket", row.liquidityBucket());
        body.put("risk_bucket", row.riskBucket());
        body.put("tags", row.tags());
        body.put("confidence", row.confidence());
        body.put("rationale", row.rationale());
        body.put("generated_at", row.generatedAt() == null ? null : row.generatedAt().toString());
        body.put("updated_at", row.updatedAt() == null ? null : row.updatedAt().toString());
        body.put("generated_age_ms", row.generatedAgeMs());
        body.put("updated_age_ms", row.updatedAgeMs());
        return body;
    }

    private static Map<String, Object> semanticQuoteBody(SemanticMarketMetadataRow row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("last_event_ts_ms", row.lastEventTsMs());
        body.put("last_canonical_event_id", row.lastCanonicalEventId());
        body.put("last_canonical_commit_seq", row.lastCanonicalCommitSeq());
        body.put("best_bid_micros", row.bestBidMicros());
        body.put("best_ask_micros", row.bestAskMicros());
        body.put("midpoint_micros", row.midpointMicros());
        body.put("open_interest", row.openInterest());
        body.put("latest_state_updated_at",
            row.latestStateUpdatedAt() == null ? null : row.latestStateUpdatedAt().toString());
        body.put("latest_state_age_ms", row.latestStateAgeMs());
        body.put("freshness_status", row.latestStateUpdatedAt() == null ? "missing_latest_state" : "available");
        return body;
    }

    private static String semanticTreemapGroupBy(String raw) {
        String value = normalize(raw);
        if (value == null) {
            return "sector";
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "sector", "subsector", "event_type", "tag" -> value.toLowerCase(Locale.ROOT);
            default -> throw new IllegalArgumentException("group_by must be sector, subsector, event_type, or tag");
        };
    }

    private static List<String> semanticGroupKeys(SemanticMarketMetadataRow row, String groupBy) {
        return switch (groupBy) {
            case "subsector" -> List.of(fallbackGroup(row.subsector()));
            case "event_type" -> List.of(fallbackGroup(row.eventType()));
            case "tag" -> row.tags().isEmpty()
                ? List.of("untagged")
                : row.tags().stream().map(FrontendAdapterServer::fallbackGroup).distinct().toList();
            case "sector" -> List.of(fallbackGroup(row.sector()));
            default -> List.of("unknown");
        };
    }

    private static String fallbackGroup(String value) {
        String normalized = normalize(value);
        return normalized == null ? "unknown" : normalized;
    }

    private static long semanticTreemapValue(SemanticMarketMetadataRow row) {
        Long openInterest = row.openInterest();
        return openInterest != null && openInterest > 0L ? openInterest : 1L;
    }

    private static boolean isSemanticMetadataStatus(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "generated", "review_required", "failed", "rate_limited" -> true;
            default -> false;
        };
    }

    private static boolean truthy(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "y", "on" -> true;
            default -> false;
        };
    }

    private static String firstNonBlank(String... values) {
        String value = firstNonBlankOrNull(values);
        return value == null ? "" : value;
    }

    private static String firstNonBlankOrNull(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class SemanticTreemapGroup {
        private final String groupBy;
        private final String key;
        private final List<Map<String, Object>> leaves = new ArrayList<>();
        private long value;
        private long generatedCount;
        private long reviewRequiredCount;
        private long failedCount;
        private long rateLimitedCount;
        private BigDecimal confidenceSum = BigDecimal.ZERO;
        private long confidenceCount;

        private SemanticTreemapGroup(String groupBy, String key) {
            this.groupBy = groupBy;
            this.key = key;
        }

        private void add(SemanticMarketMetadataRow row) {
            long leafValue = semanticTreemapValue(row);
            value += leafValue;
            switch (row.semanticStatus()) {
                case "generated" -> generatedCount++;
                case "review_required" -> reviewRequiredCount++;
                case "failed" -> failedCount++;
                case "rate_limited" -> rateLimitedCount++;
                default -> {
                }
            }
            if (row.confidence() != null) {
                confidenceSum = confidenceSum.add(row.confidence());
                confidenceCount++;
            }
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("label", firstNonBlank(row.marketTitle(), row.marketTicker()));
            leaf.put("market_ticker", row.marketTicker());
            leaf.put("title", row.marketTitle());
            leaf.put("market_status", row.marketStatus());
            leaf.put("value", leafValue);
            leaf.put("color_metric", row.confidence());
            leaf.put("semantic_status", row.semanticStatus());
            leaf.put("metadata_confidence", row.confidence());
            leaf.put("sector", row.sector());
            leaf.put("subsector", row.subsector());
            leaf.put("event_type", row.eventType());
            leaf.put("tags", row.tags());
            leaf.put("open_interest", row.openInterest());
            leaf.put("quote", semanticQuoteBody(row));
            leaves.add(leaf);
        }

        private Map<String, Object> body() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", key);
            body.put("label", key);
            body.put("group_by", groupBy);
            body.put("value", value);
            body.put("count", leaves.size());
            body.put("generated_count", generatedCount);
            body.put("review_required_count", reviewRequiredCount);
            body.put("failed_count", failedCount);
            body.put("rate_limited_count", rateLimitedCount);
            body.put("average_confidence", confidenceCount == 0L
                ? null
                : confidenceSum.divide(BigDecimal.valueOf(confidenceCount), java.math.MathContext.DECIMAL64));
            body.put("leaves", leaves);
            return body;
        }
    }

    private static Map<String, Object> semanticMetadataStatusBody(OperatorSemanticMetadataStatus status) {
        OperatorSemanticMetadataStatus view = status == null
            ? OperatorSemanticMetadataStatus.disabled("", "", "")
            : status;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", view.status());
        body.put("configured", view.configured());
        body.put("model", view.model());
        body.put("fallback_model", view.fallbackModel());
        body.put("taxonomy_version", view.taxonomyVersion());
        body.put("generated_count", view.generatedCount());
        body.put("review_required_count", view.reviewRequiredCount());
        body.put("failed_count", view.failedCount());
        body.put("rate_limited_count", view.rateLimitedCount());
        body.put("last_generated_at", view.lastGeneratedAt() == null ? null : view.lastGeneratedAt().toString());
        body.put("last_generated_age_ms", view.lastGeneratedAgeMs());
        body.put("runtime_status", "offline_batch_only");
        body.put("runtime_supported", false);
        body.put("execution_enabled", false);
        body.put("read_api", semanticMetadataReadApiStatus(view));
        if (view.error() != null) {
            body.put("error", operatorVisibleError(view.error()));
        }
        return body;
    }

    private static Map<String, Object> semanticMetadataReadApiStatus(OperatorSemanticMetadataStatus status) {
        long rowCount = status.generatedCount()
            + status.reviewRequiredCount()
            + status.failedCount()
            + status.rateLimitedCount();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", status.configured());
        body.put("available", status.configured() && !"unavailable".equals(status.status()));
        body.put("row_count", rowCount);
        body.put("endpoints", SEMANTIC_METADATA_ENDPOINTS);
        return body;
    }

    private static String operatorVisibleError(String message) {
        return OperatorRedactor.redact(message);
    }

    private static Map<String, Object> dataFreshnessBody(FrontendFeatureStore.DataFreshness freshness) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latest_event_ts_ms", freshness.latestEventTsMs());
        body.put("latest_event_age_ms", freshness.latestEventAgeMs());
        body.put("symbol", freshness.symbol());
        body.put("feature_name", freshness.featureName());
        body.put("source_event_id", freshness.sourceEventId());
        body.put("source_kind", freshness.sourceKind());
        body.put("synthetic", freshness.synthetic());
        body.put("live_data_observed", freshness.liveDataObserved());
        body.put("store_sequence", freshness.storeSequence());
        return body;
    }

    private static Map<String, Object> productReadinessBody(
        FrontendFeatureStore.DataFreshness freshness,
        FeatureOutputRefreshStatus status
    ) {
        FeatureOutputRefreshStatus refresh = status == null ? FeatureOutputRefreshStatus.disabled() : status;
        List<String> reasons = new ArrayList<>();
        boolean stale = false;
        if (freshness.latestEventTsMs() == null) {
            stale = true;
            reasons.add("no_feature_output");
        } else if (freshness.latestEventAgeMs() == null
            || freshness.latestEventAgeMs() > DATA_FRESHNESS_STALE_AFTER_MS) {
            stale = true;
            reasons.add("stale_feature_output");
        }
        if (freshness.synthetic()) {
            reasons.add("synthetic_freshness");
        }
        if (refresh.enabled() && !refresh.running()) {
            reasons.add("feature_refresh_stopped");
        }
        if (refresh.refreshErrors() > 0L && latestRefreshAttemptFailed(refresh)) {
            reasons.add("feature_refresh_errors");
        }
        boolean degraded = !reasons.isEmpty() && !stale;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", reasons.isEmpty() ? "ok" : stale ? "stale" : "degraded");
        body.put("stale", stale);
        body.put("degraded", degraded);
        body.put("stale_after_ms", DATA_FRESHNESS_STALE_AFTER_MS);
        body.put("reasons", reasons);
        return body;
    }

    private static boolean latestRefreshAttemptFailed(FeatureOutputRefreshStatus status) {
        if (status.lastErrorAt() == null) {
            return false;
        }
        return status.lastSuccessAt() == null || status.lastErrorAt().isAfter(status.lastSuccessAt());
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
        String sourceKind = FrontendSyntheticData.sourceKind(metadata);
        body.put("source_kind", sourceKind);
        body.put("synthetic", FrontendSyntheticData.isSynthetic(sourceKind));
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
        String sourceKind = FrontendSyntheticData.sourceKind(metadata);
        entry.put("source_kind", sourceKind);
        entry.put("synthetic", FrontendSyntheticData.isSynthetic(sourceKind));
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
        String sourceKind = FrontendSyntheticData.sourceKind(metadata);
        body.put("source_kind", sourceKind);
        body.put("synthetic", FrontendSyntheticData.isSynthetic(sourceKind));
    }

    private boolean includeSmokeMarkets(Map<String, String> params) {
        String override = params.get("include_smoke");
        if (override == null) {
            return config.includeSmokeMarkets();
        }
        return switch (override.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "y", "on" -> true;
            case "0", "false", "no", "n", "off" -> false;
            default -> config.includeSmokeMarkets();
        };
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

    private static List<String> parseSymbols(String csv) {
        List<String> symbols = new ArrayList<>();
        for (String symbol : csv.split(",")) {
            String trimmed = symbol.trim();
            if (!trimmed.isEmpty()) {
                symbols.add(trimmed);
            }
        }
        return symbols;
    }

    private static long parseQuoteUpdateTimeoutMs(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_QUOTE_UPDATE_TIMEOUT_MS;
        }
        long parsed = parseNonNegativeLong(raw, "timeout_ms");
        if (parsed < 1L) {
            throw new IllegalArgumentException("timeout_ms must be positive");
        }
        return Math.min(parsed, MAX_QUOTE_UPDATE_TIMEOUT_MS);
    }

    private static long parseNonNegativeLong(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        if (parsed < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return parsed;
    }

    private static Long parseOptionalMillis(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseNonNegativeLong(raw, name);
    }

    private static long toMillis(long timestamp) {
        return timestamp > 10_000_000_000L ? timestamp : timestamp * 1000L;
    }

    private static String staticContentType(String assetName) {
        if (assetName.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (assetName.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (assetName.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
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

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicLong sequence = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private void applyCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        write(exchange, status, "application/json; charset=utf-8", mapper.writeValueAsString(body));
    }

    private void writeQuoteStreamEvent(
        OutputStream out,
        List<String> symbols,
        long sequence,
        boolean changed
    ) throws IOException {
        Map<String, Object> body = quotesBody(symbols, sequence);
        body.put("changed", changed);
        writeSseFrame(out, "event: quotes\n" + "data: " + mapper.writeValueAsString(body) + "\n\n");
        quoteStreamEvents.increment();
    }

    private void writeQuoteStreamHeartbeat(OutputStream out) throws IOException {
        writeSseFrame(out, ": heartbeat " + System.currentTimeMillis() + "\n\n");
        quoteStreamHeartbeats.increment();
    }

    private static void writeSseFrame(OutputStream out, String frame) throws IOException {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void writeError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", OperatorRedactor.redact(message == null ? "" : message));
        write(exchange, status, "application/json; charset=utf-8", mapper.writeValueAsString(body));
    }

    private void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        writeBytes(exchange, status, contentType, bytes);
    }

    private void writeBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("content-type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
