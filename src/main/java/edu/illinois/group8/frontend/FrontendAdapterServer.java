package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.FeatureOutputReadRequest;
import edu.illinois.group8.storage.db.FeatureOutputReader;
import edu.illinois.group8.storage.db.JdbcMarketAssetCatalogReader;
import edu.illinois.group8.storage.db.JdbcMarketCapabilityReader;
import edu.illinois.group8.storage.db.JdbcReplayDemoStatusReader;
import edu.illinois.group8.storage.db.MarketCapability;
import edu.illinois.group8.storage.db.MarketCapabilityPage;
import edu.illinois.group8.storage.db.MarketCapabilityReadRequest;
import edu.illinois.group8.storage.db.MarketCapabilitySummary;
import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.MarketMetadataReadRequest;
import edu.illinois.group8.storage.db.OperatorLatencyStatus;
import edu.illinois.group8.storage.db.OperatorPipelineStatus;
import edu.illinois.group8.storage.db.OperatorSemanticMetadataStatus;
import edu.illinois.group8.storage.db.ReplayDemoStatus;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

public class FrontendAdapterServer {
    private static final int DEFAULT_FEATURE_LIMIT = 100;
    private static final int MAX_FEATURE_LIMIT = 500;
    private static final int DEFAULT_MARKET_LIMIT = 100;
    private static final int MAX_MARKET_LIMIT = 20_000;
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
    private static final long DISPLAY_ELIGIBLE_WINDOW_MS = 86_400_000L;
    private static final long DISPLAY_ELIGIBLE_MIN_BARS_24H = 10L;
    private static final int DB_HISTORY_MAX_ROWS = 10_000;
    private static final long OPTIONAL_STATUS_TIMEOUT_MS = 250L;
    private static final long OPTIONAL_STATUS_CACHE_TTL_MS = 2_000L;
    private static final Set<String> STATIC_ASSETS = Set.of(
        "index.html",
        "metrics.html",
        "metrics.js",
        "metrics.css",
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
    private final Supplier<ReplayDemoStatus> replayDemoStatus;
    private volatile Supplier<HotPathLatencyStatus> hotPathLatencyStatus = HotPathLatencyStatus::disabled;
    private final FeatureOutputReader dbFeatureOutputReader;
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
    private final ExecutorService optionalStatusExecutor = Executors.newFixedThreadPool(
        4,
        daemonThreadFactory("frontend-adapter-optional-status")
    );
    private final AsyncStatusCache<OperatorPipelineStatus> operatorPipelineStatusCache = new AsyncStatusCache<>();
    private final AsyncStatusCache<OperatorSemanticMetadataStatus> semanticMetadataStatusCache = new AsyncStatusCache<>();
    private final AsyncStatusCache<ReplayDemoStatus> replayDemoStatusCache = new AsyncStatusCache<>();
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
            defaultReplayDemoStatusSupplier(),
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
        Supplier<ReplayDemoStatus> replayDemoStatus,
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
            SemanticMetadataOperatorService.create(config, System.getenv()),
            replayDemoStatus
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
            defaultReplayDemoStatusSupplier()
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
        Supplier<ReplayDemoStatus> replayDemoStatus
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
            CatalogSyncOperatorService.create(config, System.getenv()),
            replayDemoStatus,
            null
        );
    }

    FrontendAdapterServer(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        FrontendMarketMetadataCatalog metadataCatalog,
        Supplier<FeaturePlantStats> featurePlantStats,
        FeatureOutputReader dbFeatureOutputReader
    ) {
        this(
            config,
            store,
            metadataCatalog,
            featurePlantStats,
            FeatureOutputRefreshStatus::disabled,
            OperatorPipelineStatus::disabled,
            sourceEventId -> OperatorLatencyStatus.disabled(),
            () -> OperatorSemanticMetadataStatus.disabled("", "", ""),
            request -> List.of(),
            FrontendReleaseInfo.empty(),
            null,
            null,
            defaultReplayDemoStatusSupplier(),
            null,
            dbFeatureOutputReader
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
            catalogSyncOperator,
            defaultReplayDemoStatusSupplier(),
            null
        );
    }

    FrontendAdapterServer(
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
        CatalogSyncOperatorService catalogSyncOperator,
        DemoOrchestratorService demoOrchestrator
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
            catalogSyncOperator,
            defaultReplayDemoStatusSupplier(),
            demoOrchestrator
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
        Supplier<ReplayDemoStatus> replayDemoStatus,
        FrontendReleaseInfo releaseInfo,
        FeatureOutputReader dbFeatureOutputReader
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
            SemanticMetadataOperatorService.create(config, System.getenv()),
            CatalogSyncOperatorService.create(config, System.getenv()),
            replayDemoStatus,
            null,
            dbFeatureOutputReader
        );
    }

    FrontendAdapterServer(
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
        CatalogSyncOperatorService catalogSyncOperator,
        Supplier<ReplayDemoStatus> replayDemoStatus,
        DemoOrchestratorService demoOrchestrator
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
            catalogSyncOperator,
            replayDemoStatus,
            demoOrchestrator,
            null
        );
    }

    FrontendAdapterServer(
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
        CatalogSyncOperatorService catalogSyncOperator,
        Supplier<ReplayDemoStatus> replayDemoStatus,
        DemoOrchestratorService demoOrchestrator,
        FeatureOutputReader dbFeatureOutputReader
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
        this.replayDemoStatus = replayDemoStatus == null ? defaultReplayDemoStatusSupplier() : replayDemoStatus;
        this.semanticMarketMetadataReader = semanticMarketMetadataReader == null
            ? request -> List.of()
            : semanticMarketMetadataReader;
        this.semanticMetadataOperator = semanticMetadataOperator == null
            ? SemanticMetadataOperatorService.create(config, System.getenv())
            : semanticMetadataOperator;
        this.catalogSyncOperator = catalogSyncOperator == null
            ? CatalogSyncOperatorService.create(config, System.getenv())
            : catalogSyncOperator;
        this.dbFeatureOutputReader = dbFeatureOutputReader;
        this.releaseInfo = releaseInfo == null ? FrontendReleaseInfo.empty() : releaseInfo;
        this.operatorControlPlane = new OperatorControlPlane(this.config, this.releaseInfo);
        this.demoOrchestrator = demoOrchestrator == null ? DemoOrchestratorService.create(
            this.config,
            this.releaseInfo,
            this.catalogSyncOperator,
            this.semanticMetadataOperator,
            this::demoProductStatusSnapshot,
            this::demoReplayStatusSnapshot
        ) : demoOrchestrator;
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
        bind("/api/markets/capabilities", this::handleMarketCapabilities);
        bind("/api/demo/replay/status", this::handleReplayDemoStatus);
        bind("/api/semantic-metadata/markets", this::handleSemanticMetadataMarkets);
        bind("/api/semantic-metadata/treemap", this::handleSemanticMetadataTreemap);
        bind("/health", this::handleHealth);
        bind("/metrics.html", this::handleStaticAsset);
        bind("/metrics.js", this::handleStaticAsset);
        bind("/metrics.css", this::handleStaticAsset);
        bind("/metrics", this::handleMetrics);
        bind("/ops/pipeline", this::handleOpsPipeline);
        bind("/ops/latency", this::handleOpsLatency);
        bind("/ops/hot-path-latency", this::handleOpsHotPathLatency);
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

    public void setHotPathLatencyStatusSupplier(Supplier<HotPathLatencyStatus> hotPathLatencyStatus) {
        this.hotPathLatencyStatus = hotPathLatencyStatus == null ? HotPathLatencyStatus::disabled : hotPathLatencyStatus;
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
        optionalStatusExecutor.shutdownNow();
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
        boolean hasBars = hasChartBars(symbol);
        body.put("has_intraday", hasBars);
        body.put("has_seconds", hasBars);
        body.put("supported_resolutions", BarResolution.SUPPORTED);
        body.put("volume_precision", 0);
        metadata.ifPresent(row -> addMetadataFields(body, row));
        writeJson(exchange, 200, body);
    }

    private boolean hasChartBars(String symbol) {
        try {
            if (dbFeatureOutputReader == null) {
                return !store.barSeries(symbol, 0L, Long.MAX_VALUE, BarResolution.M1).bars().isEmpty();
            }
            long nowMs = System.currentTimeMillis();
            return !historySeries(
                symbol,
                nowMs - DISPLAY_ELIGIBLE_WINDOW_MS,
                nowMs,
                BarResolution.M1
            ).bars().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
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
        FrontendFeatureStore.BarSeries series;
        try {
            series = historySeries(symbol, fromMs, toMs, resolution);
        } catch (RuntimeException e) {
            writeError(exchange, 500, "history unavailable: " + operatorVisibleError(e.getMessage()));
            return;
        }
        List<Bar> bars = series.bars();
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
        body.put("source", series.source());
        writeJson(exchange, 200, body);
    }

    private FrontendFeatureStore.BarSeries historySeries(
        String symbol,
        long fromMs,
        long toMs,
        BarResolution resolution
    ) {
        if (dbFeatureOutputReader == null) {
            return store.barSeries(symbol, fromMs, toMs, resolution);
        }
        for (String featureName : List.of(
            FrontendFeatureStore.BBO_FEATURE,
            FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE,
            FrontendFeatureStore.TRADE_TAPE_FEATURE
        )) {
            List<FeatureOutput> outputs = dbFeatureOutputReader.read(
                new FeatureOutputReadRequest(
                    List.of(featureName),
                    symbol,
                    fromMs,
                    toMs,
                    DB_HISTORY_MAX_ROWS
                )
            );
            FrontendFeatureStore.BarSeries series = FrontendFeatureStore.barSeriesFromOutputs(
                outputs,
                featureName,
                fromMs,
                toMs,
                resolution
            );
            if (!series.bars().isEmpty()) {
                return series;
            }
        }
        return new FrontendFeatureStore.BarSeries(null, List.of());
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
        body.put("total_count", metadataCatalog.count(query, status, includeSmoke));
        body.put("catalog_source", metadataCatalog.source());
        body.put("markets", markets);
        writeJson(exchange, 200, body);
    }

    private void handleMarketCapabilities(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        MarketCapabilityReadRequest request;
        try {
            request = marketCapabilityReadRequest(params);
        } catch (IllegalArgumentException e) {
            writeError(exchange, 400, e.getMessage());
            return;
        }
        int subscriptionCap = marketSubscriptionCap(params);

        String responseStatus = "ok";
        String source = config.dbUrl().isBlank() ? "memory" : "db";
        String error = null;
        MarketCapabilityPage page;
        if (config.dbUrl().isBlank()) {
            page = inMemoryMarketCapabilities(request);
        } else {
            try {
                page = JdbcMarketCapabilityReader
                    .fromDriverManager(config.dbUrl(), config.dbUser(), config.dbPassword())
                    .readPage(request);
            } catch (RuntimeException e) {
                responseStatus = "fallback";
                source = "memory";
                error = e.getMessage();
                page = inMemoryMarketCapabilities(request);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", responseStatus);
        body.put("source", source);
        body.put("catalog_source", source);
        body.put("limit", page.limit());
        body.put("offset", page.offset());
        body.put("count", page.markets().size());
        body.put("total_count", page.totalCount());
        body.put("has_more", page.offset() + page.markets().size() < page.totalCount());
        body.put("summary", marketCapabilitySummaryBody(page.summary(), subscriptionCap));
        body.put("markets", page.markets().stream()
            .map(FrontendAdapterServer::marketCapabilityBody)
            .toList());
        if (error != null) {
            body.put("error", operatorVisibleError(error));
        }
        writeJson(exchange, 200, body);
    }

    private void handleReplayDemoStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        ReplayDemoStatus status = currentReplayDemoStatus();
        body.put("status", status.status());
        body.put("generated_at", java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        body.put("replay_demo", replayDemoStatusBody(status));
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
        health.put("operator_pipeline", operatorPipelineStatusBody(cachedOperatorPipelineStatus()));
        health.put("semantic_metadata", semanticMetadataStatusBody(cachedSemanticMetadataStatus()));
        health.put("replay_demo", replayDemoStatusBody(cachedReplayDemoStatus()));
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
        boolean defaultSourceEvent = sourceEventId.isBlank();
        FrontendFeatureStore.DataFreshness freshness = null;
        if (sourceEventId.isBlank()) {
            freshness = store.latestFreshness(System.currentTimeMillis());
            sourceEventId = freshness.sourceEventId();
        }
        OperatorLatencyStatus latency;
        if (sourceEventId == null || sourceEventId.isBlank()) {
            latency = OperatorLatencyStatus.missing("", "missing_source_event_id");
        } else {
            latency = readOperatorLatencyBounded(sourceEventId, defaultSourceEvent);
        }
        Map<String, Object> body = operatorLatencyStatusBody(latency);
        if (defaultSourceEvent && freshness != null && !"ok".equals(latency.status())) {
            body.put("fallback_source", "latest_state_freshness");
            body.put("latest_state_age_ms", freshness.latestEventAgeMs());
            body.put("latest_state_source_event_id", freshness.sourceEventId());
            body.put("store_sequence", freshness.storeSequence());
        }
        body.put("generated_at", java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        writeJson(exchange, 200, body);
    }

    private void handleOpsHotPathLatency(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "method not allowed");
            return;
        }
        HotPathLatencyStatus status = readHotPathLatencyStatus();
        Map<String, Object> body = hotPathLatencyStatusBody(status);
        body.put("generated_at", java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        writeJson(exchange, 200, body);
    }

    private HotPathLatencyStatus readHotPathLatencyStatus() {
        try {
            return hotPathLatencyStatus.get();
        } catch (RuntimeException e) {
            return HotPathLatencyStatus.unavailable(e.getMessage());
        }
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
        body.put("pipeline", operatorPipelineStatusBody(cachedOperatorPipelineStatus()));
        body.put("catalog_sync", catalogSyncOperator.statusBody());
        body.put("catalog_sync_run", catalogSyncOperator.statusBody());
        body.put("semantic_metadata", semanticMetadataStatusBody(cachedSemanticMetadataStatus()));
        body.put("semantic_metadata_run", semanticMetadataOperator.statusBody());
        body.put("demo_orchestrator", demoOrchestrator.statusBody());
        body.put("replay_demo", replayDemoStatusBody(cachedReplayDemoStatus()));
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
        body.put("pipeline", operatorPipelineStatusBody(cachedOperatorPipelineStatus()));
        body.put("semantic_metadata", semanticMetadataStatusBody(cachedSemanticMetadataStatus()));
        body.put("replay_demo", replayDemoStatusBody(cachedReplayDemoStatus()));
        body.put("catalog_sync", catalogSyncOperator.statusBody());
        body.put("semantic_metadata_run", semanticMetadataOperator.statusBody());
        body.put("market_metadata", Map.of(
            "source", metadataCatalog.source(),
            "status", metadataCatalog.loadStatus().name().toLowerCase(Locale.ROOT),
            "markets", metadataCatalog.size()
        ));
        return body;
    }

    private Map<String, Object> demoReplayStatusSnapshot() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generated_at", java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        body.put("replay_demo", replayDemoStatusBody(currentReplayDemoStatus()));
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
            JdbcMarketAssetCatalogReader reader =
                JdbcMarketAssetCatalogReader.fromDriverManager(config.dbUrl(), config.dbUser(), config.dbPassword());
            List<MarketMetadata> rows =
                reader.read(MarketMetadataReadRequest.search(null, null, config.metadataMaxRows()));
            metadataCatalog = FrontendMarketMetadataCatalog.loaded("db", rows);
            lastCatalogMetadataRefreshKey = key;
        } catch (RuntimeException e) {
            metadataCatalog = FrontendMarketMetadataCatalog.unavailable("db", e.getMessage());
        }
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!wantsRawPrometheusMetrics(exchange) && wantsMetricsDashboard(exchange)) {
            serveStaticAsset(exchange, "metrics.html");
            return;
        }
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
        appendHotPathLatencyPrometheusMetrics(body, readHotPathLatencyStatus());
        write(exchange, 200, "text/plain; charset=utf-8", body.toString());
    }

    private static void appendHotPathLatencyPrometheusMetrics(
        StringBuilder body,
        HotPathLatencyStatus status
    ) {
        HotPathLatencyStatus view = status == null ? HotPathLatencyStatus.disabled() : status;
        Map<String, String> statusLabels = new LinkedHashMap<>();
        statusLabels.put("status", view.status());
        statusLabels.put("source", view.source());
        appendPrometheusGauge(
            body,
            "frontend_adapter_hot_path_latency_status",
            statusLabels,
            1L
        );
        for (HotPathLatencyStatus.Stage stage : view.stages()) {
            for (HotPathLatencyStatus.Series series : stage.series()) {
                Map<String, String> labels = hotPathPrometheusLabels(stage, series);
                appendPrometheusGauge(
                    body,
                    "frontend_adapter_hot_path_latency_recent_count",
                    labels,
                    series.recentCount()
                );
                appendNullablePrometheusGauge(
                    body,
                    "frontend_adapter_hot_path_latency_recent_p50_ns",
                    labels,
                    series.p50Ns()
                );
                appendNullablePrometheusGauge(
                    body,
                    "frontend_adapter_hot_path_latency_recent_p90_ns",
                    labels,
                    series.p90Ns()
                );
                appendNullablePrometheusGauge(
                    body,
                    "frontend_adapter_hot_path_latency_recent_p95_ns",
                    labels,
                    series.p95Ns()
                );
                appendNullablePrometheusGauge(
                    body,
                    "frontend_adapter_hot_path_latency_recent_p99_ns",
                    labels,
                    series.p99Ns()
                );
                appendNullablePrometheusGauge(
                    body,
                    "frontend_adapter_hot_path_latency_recent_p999_ns",
                    labels,
                    series.p999Ns()
                );
                appendNullablePrometheusGauge(
                    body,
                    "frontend_adapter_hot_path_latency_recent_max_ns",
                    labels,
                    series.maxNs()
                );
                appendNullablePrometheusGauge(
                    body,
                    "frontend_adapter_hot_path_latency_avg_ns",
                    labels,
                    series.avgNs()
                );
            }
        }
    }

    private static Map<String, String> hotPathPrometheusLabels(
        HotPathLatencyStatus.Stage stage,
        HotPathLatencyStatus.Series series
    ) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("stage", stage.id());
        labels.put("source", stage.source());
        labels.put("metric", stage.metric());
        series.labels().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                String key = entry.getKey();
                String labelKey = switch (key) {
                    case "stage", "source", "metric" -> "upstream_" + key;
                    default -> key;
                };
                labels.putIfAbsent(labelKey, entry.getValue());
            });
        return labels;
    }

    private static void appendNullablePrometheusGauge(
        StringBuilder body,
        String metricName,
        Map<String, String> labels,
        Long value
    ) {
        if (value != null) {
            appendPrometheusGauge(body, metricName, labels, value);
        }
    }

    private static void appendPrometheusGauge(
        StringBuilder body,
        String metricName,
        Map<String, String> labels,
        long value
    ) {
        body.append(metricName);
        appendPrometheusLabels(body, labels);
        body.append(' ').append(value).append('\n');
    }

    private static void appendPrometheusLabels(StringBuilder body, Map<String, String> labels) {
        if (labels.isEmpty()) {
            return;
        }
        body.append('{');
        boolean first = true;
        for (Map.Entry<String, String> label : labels.entrySet()) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append(label.getKey())
                .append("=\"")
                .append(escape(label.getValue() == null ? "" : label.getValue()))
                .append('"');
        }
        body.append('}');
    }

    private static boolean wantsMetricsDashboard(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept == null || accept.isBlank()) {
            return false;
        }
        String normalized = accept.toLowerCase(Locale.ROOT);
        return normalized.contains("text/html")
            && !normalized.contains("text/plain")
            && !normalized.contains("openmetrics");
    }

    private static boolean wantsRawPrometheusMetrics(HttpExchange exchange) {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String format = params.getOrDefault("format", "");
        String raw = params.getOrDefault("raw", "");
        return "prometheus".equalsIgnoreCase(format)
            || "text".equalsIgnoreCase(format)
            || "1".equals(raw)
            || "true".equalsIgnoreCase(raw);
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
        serveStaticAsset(exchange, assetName);
    }

    private void serveStaticAsset(HttpExchange exchange, String assetName) throws IOException {
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

    private ReplayDemoStatus currentReplayDemoStatus() {
        try {
            ReplayDemoStatus status = replayDemoStatus.get();
            return status == null
                ? ReplayDemoStatus.unavailable(JdbcReplayDemoStatusReader.DEFAULT_REPLAY_ID, "status supplier returned null")
                : status;
        } catch (RuntimeException e) {
            return ReplayDemoStatus.unavailable(JdbcReplayDemoStatusReader.DEFAULT_REPLAY_ID, e.getMessage());
        }
    }

    private OperatorPipelineStatus cachedOperatorPipelineStatus() {
        return operatorPipelineStatusCache.get(
            operatorPipelineStatus,
            reason -> OperatorPipelineStatus.unavailable(config.featurePlantCursorName(), reason),
            optionalStatusExecutor,
            OPTIONAL_STATUS_TIMEOUT_MS,
            OPTIONAL_STATUS_CACHE_TTL_MS
        );
    }

    private OperatorSemanticMetadataStatus cachedSemanticMetadataStatus() {
        return semanticMetadataStatusCache.get(
            operatorSemanticMetadataStatus,
            reason -> OperatorSemanticMetadataStatus.unavailable(
                config.llmMetadataModel(),
                config.llmMetadataFallbackModel(),
                config.llmMetadataTaxonomyVersion(),
                reason
            ),
            optionalStatusExecutor,
            OPTIONAL_STATUS_TIMEOUT_MS,
            OPTIONAL_STATUS_CACHE_TTL_MS
        );
    }

    private ReplayDemoStatus cachedReplayDemoStatus() {
        return replayDemoStatusCache.get(
            this::currentReplayDemoStatus,
            reason -> ReplayDemoStatus.unavailable(JdbcReplayDemoStatusReader.DEFAULT_REPLAY_ID, reason),
            optionalStatusExecutor,
            OPTIONAL_STATUS_TIMEOUT_MS,
            OPTIONAL_STATUS_CACHE_TTL_MS
        );
    }

    private OperatorLatencyStatus readOperatorLatencyBounded(String sourceEventId, boolean defaultSourceEvent) {
        CompletableFuture<OperatorLatencyStatus> future = CompletableFuture.supplyAsync(
            () -> operatorLatencyStatus.apply(sourceEventId),
            optionalStatusExecutor
        );
        try {
            return future.get(OPTIONAL_STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return defaultSourceEvent
                ? OperatorLatencyStatus.missing(sourceEventId, "latency_reader_timeout")
                : OperatorLatencyStatus.unavailable(sourceEventId, "latency_reader_timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperatorLatencyStatus.unavailable(sourceEventId, "latency_reader_interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException) {
                return OperatorLatencyStatus.missing(sourceEventId, cause.getMessage());
            }
            return OperatorLatencyStatus.unavailable(sourceEventId, errorMessage(cause));
        } catch (RuntimeException e) {
            return OperatorLatencyStatus.unavailable(sourceEventId, e.getMessage());
        }
    }

    private static Map<String, Object> replayDemoStatusBody(ReplayDemoStatus status) {
        ReplayDemoStatus view = status == null
            ? ReplayDemoStatus.disabled(JdbcReplayDemoStatusReader.DEFAULT_REPLAY_ID)
            : status;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", view.status());
        body.put("replay_id", view.replayId());
        body.put("market_count", view.marketCount());
        body.put("canonical_event_count", view.canonicalEventCount());
        body.put("feature_output_count", view.featureOutputCount());
        body.put("latest_market_state_count", view.latestMarketStateCount());
        body.put("first_event_ts_ms", view.firstEventTsMs());
        body.put("last_event_ts_ms", view.lastEventTsMs());
        body.put("first_canonical_commit_seq", view.firstCanonicalCommitSeq());
        body.put("last_canonical_commit_seq", view.lastCanonicalCommitSeq());
        body.put("available_symbols", view.availableSymbols());
        body.put("featureplant_projected", view.featurePlantProjected());
        body.put("dataset_ready", view.canonicalEventCount() > 0L && view.marketCount() > 0L);
        if (view.error() != null) {
            body.put("error", operatorVisibleError(view.error()));
        }
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

    private static Map<String, Object> hotPathLatencyStatusBody(HotPathLatencyStatus status) {
        HotPathLatencyStatus view = status == null ? HotPathLatencyStatus.disabled() : status;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", view.status());
        body.put("source", view.source());
        body.put("note", view.note());
        body.put("stages", view.stages().stream().map(FrontendAdapterServer::hotPathStageBody).toList());
        if (view.error() != null) {
            body.put("error", operatorVisibleError(view.error()));
        }
        return body;
    }

    private static Map<String, Object> hotPathStageBody(HotPathLatencyStatus.Stage stage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", stage.id());
        body.put("label", stage.label());
        body.put("status", stage.status());
        body.put("source", stage.source());
        body.put("metric", stage.metric());
        body.put("note", stage.note());
        body.put("series", stage.series().stream().map(FrontendAdapterServer::hotPathSeriesBody).toList());
        return body;
    }

    private static Map<String, Object> hotPathSeriesBody(HotPathLatencyStatus.Series series) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("labels", series.labels());
        body.put("count", series.count());
        body.put("recent_count", series.recentCount());
        body.put("p50_ns", series.p50Ns());
        body.put("p90_ns", series.p90Ns());
        body.put("p95_ns", series.p95Ns());
        body.put("p99_ns", series.p99Ns());
        body.put("p999_ns", series.p999Ns());
        body.put("max_ns", series.maxNs());
        body.put("avg_ns", series.avgNs());
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
        body.put("base_market_key", row.baseMarketKey());
        body.put("base_market_ticker", row.baseMarketKey());
        body.put("side_tag", row.sideTag());
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
        body.put("aggregate_open_interest", row.aggregateOpenInterest());
        body.put("base_open_interest", row.aggregateOpenInterest());
        body.put("current_midpoint_micros", row.currentMidpointMicros());
        body.put("midpoint_24h_ago_micros", row.midpoint24hAgoMicros());
        body.put("price_change_24h_micros", row.priceChange24hMicros());
        body.put("midpoint_reference_micros", row.midpoint24hAgoMicros());
        body.put("midpoint_change_micros", row.priceChange24hMicros());
        body.put("midpoint_change_pct", midpointChangePct(row));
        body.put("latest_state_updated_at",
            row.latestStateUpdatedAt() == null ? null : row.latestStateUpdatedAt().toString());
        body.put("latest_state_age_ms", row.latestStateAgeMs());
        body.put("freshness_status", row.latestStateUpdatedAt() == null ? "missing_latest_state" : "available");
        return body;
    }

    private static Double midpointChangePct(SemanticMarketMetadataRow row) {
        Long reference = row.midpoint24hAgoMicros();
        Long change = row.priceChange24hMicros();
        if (reference == null || reference == 0 || change == null) {
            return null;
        }
        return change.doubleValue() / reference.doubleValue();
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
            case "subsector" -> List.of(canonicalGroupKey(row.subsector()));
            case "event_type" -> List.of(canonicalGroupKey(row.eventType()));
            case "tag" -> row.tags().isEmpty()
                ? List.of("untagged")
                : row.tags().stream().map(FrontendAdapterServer::canonicalGroupKey).distinct().toList();
            case "sector" -> List.of(canonicalGroupKey(row.sector()));
            default -> List.of("unknown");
        };
    }

    private static String canonicalGroupKey(String value) {
        String normalized = normalize(value);
        return normalized == null ? "unknown" : normalized.toLowerCase(Locale.ROOT);
    }

    private static String semanticGroupLabel(String key) {
        if (key == null || key.isBlank()) {
            return "Unknown";
        }
        String[] words = key.replace('_', ' ').replace('-', ' ').split("\\s+");
        List<String> titled = new ArrayList<>();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (word.length() <= 3 && word.equals(word.toUpperCase(Locale.ROOT))) {
                titled.add(word);
                continue;
            }
            titled.add(word.substring(0, 1).toUpperCase(Locale.ROOT)
                + word.substring(1).toLowerCase(Locale.ROOT));
        }
        return titled.isEmpty() ? "Unknown" : String.join(" ", titled);
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
            leaf.put("base_market_key", row.baseMarketKey());
            leaf.put("base_market_ticker", row.baseMarketKey());
            leaf.put("side_tag", row.sideTag());
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
            leaf.put("aggregate_open_interest", row.aggregateOpenInterest());
            leaf.put("base_open_interest", row.aggregateOpenInterest());
            leaf.put("current_midpoint_micros", row.currentMidpointMicros());
            leaf.put("midpoint_24h_ago_micros", row.midpoint24hAgoMicros());
            leaf.put("price_change_24h_micros", row.priceChange24hMicros());
            leaf.put("midpoint_reference_micros", row.midpoint24hAgoMicros());
            leaf.put("midpoint_change_micros", row.priceChange24hMicros());
            leaf.put("midpoint_change_pct", midpointChangePct(row));
            leaf.put("quote", semanticQuoteBody(row));
            leaves.add(leaf);
        }

        private Map<String, Object> body() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", key);
            body.put("label", semanticGroupLabel(key));
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

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return "status_unavailable";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
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
        body.put("catalog_source", isFeatureOnlyCatalogEntry(metadata) ? "feature_outputs" : "market_metadata");
        body.put("synthetic", FrontendSyntheticData.isSynthetic(sourceKind));
        return body;
    }

    private static Map<String, Object> marketCapabilityBody(MarketCapability capability) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("market_ticker", capability.marketTicker());
        body.put("event_ticker", capability.eventTicker());
        body.put("series_ticker", capability.seriesTicker());
        body.put("status", capability.status());
        body.put("catalog_source", capability.catalogSource());
        body.put("has_latest_state", capability.hasLatestState());
        body.put("has_quote", capability.hasQuote());
        body.put("has_live_quote", "live_quote".equals(capability.quoteStatus()));
        body.put("quote_event_ts_ms", capability.quoteEventTsMs());
        body.put("quote_age_ms", capability.quoteAgeMs());
        body.put("quote_status", capability.quoteStatus());
        body.put("has_bbo_history", capability.hasBboHistory());
        body.put("chartable_from_bbo", capability.chartableFromBbo());
        body.put("chartable_from_ticker_snapshot", capability.chartableFromTickerSnapshot());
        body.put("chartable_from_trade_tape", capability.chartableFromTradeTape());
        body.put("best_chart_source", capability.bestChartSource());
        body.put("chartable_1h", capability.chartable1h());
        body.put("chartable_24h", capability.chartable24h());
        body.put("chartable", capability.chartable());
        body.put("chart_status", capability.chartStatus());
        body.put("chart_reason", capability.chartReason());
        body.put("semantic_status", capability.semanticStatus());
        body.put("semantic_sector", capability.semanticSector());
        body.put("semantic_subsector", capability.semanticSubsector());
        body.put("semantic_event_type", capability.semanticEventType());
        body.put("feature_count", capability.featureCount());
        body.put("bbo_sample_count", capability.bboSampleCount());
        body.put("trade_sample_count", capability.tradeSampleCount());
        body.put("ticker_sample_count", capability.tickerSampleCount());
        body.put("bars_24h_count", capability.historyBars24hCount());
        body.put("history_bars_24h", capability.historyBars24hCount());
        body.put("trade_24h_count", capability.trade24hCount());
        body.put("quote_24h_count", capability.quote24hCount());
        body.put("last_event_ts_ms", capability.lastEventTsMs());
        body.put("liquidity_rank", capability.liquidityRank());
        body.put("eligible", capability.displayEligible());
        body.put("display_eligible", capability.displayEligible());
        body.put("feature_counts", Map.of(
            "bbo", capability.bboSampleCount(),
            "trade", capability.tradeSampleCount(),
            "ticker", capability.tickerSampleCount(),
            "total", capability.featureCount()
        ));
        body.put("source_kind", sourceKind(capability.marketTicker()));
        body.put("synthetic", FrontendSyntheticData.isSynthetic(String.valueOf(body.get("source_kind"))));
        return body;
    }

    private static Map<String, Object> marketCapabilitySummaryBody(MarketCapabilitySummary summary, int subscriptionCap) {
        MarketCapabilitySummary view = summary == null ? MarketCapabilitySummary.empty() : summary;
        long subscribedCount = subscriptionCap <= 0
            ? view.displayEligibleCount()
            : Math.min(view.displayEligibleCount(), subscriptionCap);
        long cappedCount = Math.max(0L, view.displayEligibleCount() - subscribedCount);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total_assets", view.totalAssets());
        body.put("eligible_count", view.displayEligibleCount());
        body.put("display_eligible_count", view.displayEligibleCount());
        body.put("filtered_out_count", view.displayIneligibleCount());
        body.put("excluded_count", view.displayIneligibleCount());
        body.put("subscription_cap", subscriptionCap);
        body.put("subscribed_count", subscribedCount);
        body.put("capped_count", cappedCount);
        body.put("chartable_count", view.chartableCount());
        body.put("quote_count", view.quoteCount());
        body.put("stale_quote_count", view.staleQuoteCount());
        body.put("semantic_generated_count", view.semanticGeneratedCount());
        body.put("semantic_review_required_count", view.semanticReviewRequiredCount());
        body.put("semantic_failed_count", view.semanticFailedCount());
        body.put("semantic_rate_limited_count", view.semanticRateLimitedCount());
        body.put("semantic_missing_count", view.semanticMissingCount());
        body.put("semantic_eligible_generated_count", view.semanticEligibleGeneratedCount());
        body.put("semantic_eligible_missing_count", view.semanticEligibleMissingCount());
        body.put("metadata_only_count", view.metadataOnlyCount());
        body.put("min_bars_24h", DISPLAY_ELIGIBLE_MIN_BARS_24H);
        return body;
    }

    private MarketCapabilityReadRequest marketCapabilityReadRequest(Map<String, String> params) {
        int limit = parseLimit(
            params.get("limit"),
            MarketCapabilityReadRequest.DEFAULT_LIMIT,
            MarketCapabilityReadRequest.MAX_LIMIT
        );
        int offset = parseOffset(params.get("offset"));
        String taxonomyVersion = firstNonBlank(
            params.get("taxonomy_version"),
            config.llmMetadataTaxonomyVersion(),
            MarketCapabilityReadRequest.DEFAULT_TAXONOMY_VERSION
        );
        return new MarketCapabilityReadRequest(
            firstNonBlankOrNull(params.get("q"), params.get("search"), params.get("query")),
            firstNonBlankOrNull(params.get("market_status"), params.get("status")),
            firstNonBlankOrNull(params.get("capability"), params.get("filter")),
            limit,
            offset,
            taxonomyVersion,
            includeSmokeMarkets(params),
            booleanParam(params, "include_ineligible", false)
        );
    }

    private MarketCapabilityPage inMemoryMarketCapabilities(MarketCapabilityReadRequest request) {
        List<MarketCapability> all = inMemoryCapabilityRows(request);
        MarketCapabilitySummary summary = summarizeCapabilities(all);
        List<MarketCapability> filtered = all.stream()
            .filter(capability -> request.includeIneligible() || capability.displayEligible())
            .filter(capability -> matchesCapabilityFilter(capability, request.capabilityFilter()))
            .sorted(FrontendAdapterServer::compareCapabilities)
            .toList();
        int fromIndex = Math.min(filtered.size(), request.offset());
        int toIndex = Math.min(filtered.size(), fromIndex + request.limit());
        return new MarketCapabilityPage(
            summary,
            filtered.subList(fromIndex, toIndex),
            filtered.size(),
            request.limit(),
            request.offset()
        );
    }

    private List<MarketCapability> inMemoryCapabilityRows(MarketCapabilityReadRequest request) {
        Map<String, MarketMetadata> metadataByTicker = new LinkedHashMap<>();
        int metadataLimit = Math.max(1, metadataCatalog.size());
        for (MarketMetadata metadata : metadataCatalog.search(null, null, metadataLimit, request.includeSmoke())) {
            if (matchesBaseCapabilitySearch(metadata, request)) {
                metadataByTicker.put(metadata.marketTicker(), metadata);
            }
        }
        Map<String, SemanticMarketMetadataRow> semanticByTicker = semanticRowsByTicker(request);
        Map<String, MarketCapability> capabilities = new LinkedHashMap<>();
        for (MarketMetadata metadata : metadataByTicker.values()) {
            capabilities.put(metadata.marketTicker(), capabilityFromMemory(metadata, semanticByTicker.get(metadata.marketTicker())));
        }
        for (String symbol : store.symbols()) {
            if (!request.includeSmoke() && FrontendSyntheticData.isSmokeMarketTicker(symbol)) {
                continue;
            }
            if (capabilities.containsKey(symbol)) {
                continue;
            }
            MarketMetadata syntheticMetadata = new MarketMetadata(
                symbol,
                null,
                null,
                "indexed",
                null,
                null,
                null,
                "{}",
                "{}"
            );
            if (matchesBaseCapabilitySearch(syntheticMetadata, request)) {
                capabilities.put(symbol, capabilityFromMemory(syntheticMetadata, semanticByTicker.get(symbol)));
            }
        }
        return List.copyOf(capabilities.values());
    }

    private MarketCapability capabilityFromMemory(MarketMetadata metadata, SemanticMarketMetadataRow semantic) {
        String marketTicker = metadata.marketTicker();
        List<FeatureOutput> allFeatures = store.snapshot(marketTicker, null);
        List<FeatureOutput> bboFeatures = allFeatures.stream()
            .filter(output -> FrontendFeatureStore.BBO_FEATURE.equals(output.featureName()))
            .toList();
        List<FeatureOutput> tickerFeatures = allFeatures.stream()
            .filter(output -> FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE.equals(output.featureName()))
            .toList();
        List<FeatureOutput> tradeFeatures = allFeatures.stream()
            .filter(output -> FrontendFeatureStore.TRADE_TAPE_FEATURE.equals(output.featureName()))
            .toList();
        long nowMs = System.currentTimeMillis();
        boolean chartableFromBbo = bboFeatures.stream().anyMatch(FrontendAdapterServer::chartableOutput);
        boolean chartableFromTicker = tickerFeatures.stream().anyMatch(FrontendAdapterServer::chartableOutput);
        boolean chartableFromTrade = tradeFeatures.stream().anyMatch(FrontendAdapterServer::chartableOutput);
        long chartable1h = allFeatures.stream()
            .filter(FrontendAdapterServer::chartableOutput)
            .filter(output -> inWindow(output, nowMs, 3_600_000L))
            .count();
        long chartable24h = allFeatures.stream()
            .filter(FrontendAdapterServer::chartableOutput)
            .filter(output -> inWindow(output, nowMs, 86_400_000L))
            .count();
        long tradeCount = tradeFeatures.size();
        long tickerCount = tickerFeatures.size();
        Optional<FeatureOutput> latest = store.latest(marketTicker, FrontendFeatureStore.BBO_FEATURE);
        Long eventTsMs = latest.map(FeatureOutput::eventTsMs).orElse(null);
        Object midpoint = latest.map(output -> output.values().get("midpoint_micros")).orElse(null);
        boolean hasQuote = eventTsMs != null && midpoint != null;
        Long quoteAgeMs = eventTsMs == null ? null : Math.max(0L, nowMs - eventTsMs);
        String quoteStatus = quoteStatus(hasQuote, quoteAgeMs);
        long historyBars24h = store.barSeries(
            marketTicker,
            nowMs - DISPLAY_ELIGIBLE_WINDOW_MS,
            nowMs,
            BarResolution.M1
        ).bars().size();
        boolean displayEligible = historyBars24h >= DISPLAY_ELIGIBLE_MIN_BARS_24H;
        long trade24h = tradeFeatures.stream().filter(output -> inWindow(output, nowMs, DISPLAY_ELIGIBLE_WINDOW_MS)).count();
        long quote24h = bboFeatures.stream().filter(output -> inWindow(output, nowMs, DISPLAY_ELIGIBLE_WINDOW_MS)).count()
            + tickerFeatures.stream().filter(output -> inWindow(output, nowMs, DISPLAY_ELIGIBLE_WINDOW_MS)).count();
        Long lastEventTsMs = allFeatures.stream()
            .map(FeatureOutput::eventTsMs)
            .filter(ts -> ts != null)
            .max(Long::compareTo)
            .orElse(null);
        boolean chartable = chartableFromBbo || chartableFromTicker || chartableFromTrade;
        String bestChartSource = bestChartSource(chartableFromBbo, chartableFromTicker, chartableFromTrade);
        String chartStatus = chartable1h > 0L ? "chartable_1h"
            : chartable24h > 0L ? "chartable_24h"
            : chartable ? "chartable_history"
            : hasQuote ? "quote_only"
            : "not_chartable";
        String chartReason = chartableFromBbo ? "bbo_history_available"
            : chartableFromTicker ? "ticker_snapshot_history_available"
            : chartableFromTrade ? "trade_tape_history_available"
            : hasQuote ? "quote_without_chart_history"
            : isFeatureOnlyCatalogEntry(metadata) ? "missing_quote_and_history" : "catalog_only";
        return new MarketCapability(
            marketTicker,
            metadata.eventTicker(),
            metadata.seriesTicker(),
            metadata.status(),
            isFeatureOnlyCatalogEntry(metadata) ? "feature_outputs" : "market_metadata",
            latest.isPresent(),
            hasQuote,
            eventTsMs,
            quoteAgeMs,
            quoteStatus,
            chartableFromBbo,
            chartableFromBbo,
            chartableFromTicker,
            chartableFromTrade,
            bestChartSource,
            chartable1h > 0L,
            chartable24h > 0L,
            chartable,
            chartStatus,
            chartReason,
            semantic == null ? "missing" : semantic.semanticStatus(),
            semantic == null ? null : semantic.sector(),
            semantic == null ? null : semantic.subsector(),
            semantic == null ? null : semantic.eventType(),
            allFeatures.size(),
            bboFeatures.size(),
            tradeCount,
            tickerCount,
            historyBars24h,
            trade24h,
            quote24h,
            lastEventTsMs,
            null,
            displayEligible
        );
    }

    private Map<String, SemanticMarketMetadataRow> semanticRowsByTicker(MarketCapabilityReadRequest request) {
        try {
            List<SemanticMarketMetadataRow> rows = semanticMarketMetadataReader.read(
                new SemanticMarketMetadataReadRequest(
                    request.taxonomyVersion(),
                    null,
                    null,
                    null,
                    null,
                    request.query(),
                    500
                )
            );
            Map<String, SemanticMarketMetadataRow> byTicker = new LinkedHashMap<>();
            for (SemanticMarketMetadataRow row : rows) {
                byTicker.put(row.marketTicker(), row);
            }
            return byTicker;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static MarketCapabilitySummary summarizeCapabilities(List<MarketCapability> capabilities) {
        long total = capabilities.size();
        long chartable = 0L;
        long quote = 0L;
        long staleQuote = 0L;
        long semanticGenerated = 0L;
        long semanticReviewRequired = 0L;
        long semanticFailed = 0L;
        long semanticRateLimited = 0L;
        long semanticMissing = 0L;
        long metadataOnly = 0L;
        long displayEligible = 0L;
        long displayIneligible = 0L;
        long semanticEligibleGenerated = 0L;
        long semanticEligibleMissing = 0L;
        for (MarketCapability capability : capabilities) {
            if (capability.displayEligible()) {
                displayEligible++;
            } else {
                displayIneligible++;
            }
            if (capability.chartable()) {
                chartable++;
            }
            if (capability.hasQuote()) {
                quote++;
            }
            if ("stale_quote".equals(capability.quoteStatus())) {
                staleQuote++;
            }
            if ("generated".equals(capability.semanticStatus())) {
                semanticGenerated++;
            }
            if ("review_required".equals(capability.semanticStatus())) {
                semanticReviewRequired++;
            }
            if ("failed".equals(capability.semanticStatus())) {
                semanticFailed++;
            }
            if ("rate_limited".equals(capability.semanticStatus())) {
                semanticRateLimited++;
            }
            if ("missing".equals(capability.semanticStatus())) {
                semanticMissing++;
            }
            if (capability.displayEligible() && "generated".equals(capability.semanticStatus())) {
                semanticEligibleGenerated++;
            }
            if (capability.displayEligible() && "missing".equals(capability.semanticStatus())) {
                semanticEligibleMissing++;
            }
            if (capability.metadataOnly()) {
                metadataOnly++;
            }
        }
        return new MarketCapabilitySummary(
            total,
            chartable,
            quote,
            staleQuote,
            semanticGenerated,
            semanticReviewRequired,
            semanticFailed,
            semanticRateLimited,
            semanticMissing,
            metadataOnly,
            displayEligible,
            displayIneligible,
            semanticEligibleGenerated,
            semanticEligibleMissing
        );
    }

    private static boolean matchesBaseCapabilitySearch(MarketMetadata metadata, MarketCapabilityReadRequest request) {
        if (request.status() != null && !request.status().equalsIgnoreCase(nullToEmpty(metadata.status()))) {
            return false;
        }
        String query = request.query();
        return query == null || matchesQuery(metadata, query.toLowerCase(Locale.ROOT));
    }

    private static boolean matchesQuery(MarketMetadata metadata, String query) {
        return containsIgnoreCase(metadata.marketTicker(), query)
            || containsIgnoreCase(metadata.eventTicker(), query)
            || containsIgnoreCase(metadata.seriesTicker(), query)
            || containsIgnoreCase(metadata.status(), query);
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean matchesCapabilityFilter(MarketCapability capability, String filter) {
        return switch (filter) {
            case "all" -> true;
            case "chart_ready" -> capability.displayEligible();
            case "quote_available" -> capability.hasQuote();
            case "quote_only" -> capability.hasQuote() && !capability.chartable();
            case "quote_stale" -> "stale_quote".equals(capability.quoteStatus());
            case "metadata_only" -> capability.metadataOnly();
            case "semantic_tagged" -> capability.semanticTagged();
            case "unclassified" -> "missing".equals(capability.semanticStatus());
            default -> false;
        };
    }

    private static int compareCapabilities(MarketCapability left, MarketCapability right) {
        int leftRank = capabilityRank(left);
        int rightRank = capabilityRank(right);
        if (leftRank != rightRank) {
            return Integer.compare(leftRank, rightRank);
        }
        int bars = Long.compare(right.historyBars24hCount(), left.historyBars24hCount());
        if (bars != 0) {
            return bars;
        }
        int trades = Long.compare(right.trade24hCount(), left.trade24hCount());
        if (trades != 0) {
            return trades;
        }
        int quotes = Long.compare(right.quote24hCount(), left.quote24hCount());
        if (quotes != 0) {
            return quotes;
        }
        long leftLast = left.lastEventTsMs() == null ? Long.MIN_VALUE : left.lastEventTsMs();
        long rightLast = right.lastEventTsMs() == null ? Long.MIN_VALUE : right.lastEventTsMs();
        int last = Long.compare(rightLast, leftLast);
        if (last != 0) {
            return last;
        }
        long leftTs = left.quoteEventTsMs() == null ? Long.MIN_VALUE : left.quoteEventTsMs();
        long rightTs = right.quoteEventTsMs() == null ? Long.MIN_VALUE : right.quoteEventTsMs();
        int ts = Long.compare(rightTs, leftTs);
        return ts != 0 ? ts : left.marketTicker().compareTo(right.marketTicker());
    }

    private static int capabilityRank(MarketCapability capability) {
        if (capability.displayEligible()) {
            return 0;
        }
        if (capability.chartable1h()) {
            return 1;
        }
        if (capability.chartable24h()) {
            return 2;
        }
        if (capability.hasQuote()) {
            return 3;
        }
        if (capability.chartable()) {
            return 4;
        }
        return 5;
    }

    private static boolean chartableOutput(FeatureOutput output) {
        Long eventTsMs = output.eventTsMs();
        if (eventTsMs == null) {
            return false;
        }
        Map<String, Object> values = output.values();
        return switch (output.featureName()) {
            case FrontendFeatureStore.BBO_FEATURE -> values.get("midpoint_micros") instanceof Number;
            case FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE -> values.get("price_micros") instanceof Number
                || (values.get("yes_bid_micros") instanceof Number && values.get("yes_ask_micros") instanceof Number);
            case FrontendFeatureStore.TRADE_TAPE_FEATURE -> values.get("yes_price_micros") instanceof Number
                || values.get("no_price_micros") instanceof Number;
            default -> false;
        };
    }

    private static String bestChartSource(boolean bbo, boolean tickerSnapshot, boolean tradeTape) {
        if (bbo) {
            return "bbo";
        }
        if (tickerSnapshot) {
            return "ticker_snapshot";
        }
        return tradeTape ? "trade_tape" : null;
    }

    private static boolean inWindow(FeatureOutput output, long nowMs, long windowMs) {
        Long eventTsMs = output.eventTsMs();
        return eventTsMs != null && eventTsMs >= nowMs - windowMs && eventTsMs <= nowMs;
    }

    private static String quoteStatus(boolean hasQuote, Long quoteAgeMs) {
        if (!hasQuote || quoteAgeMs == null) {
            return "missing_quote";
        }
        return quoteAgeMs <= DATA_FRESHNESS_STALE_AFTER_MS ? "live_quote" : "stale_quote";
    }

    private static String sourceKind(String marketTicker) {
        if (FrontendSyntheticData.isSmokeMarketTicker(marketTicker)) {
            return FrontendSyntheticData.SOURCE_KIND_SMOKE;
        }
        return marketTicker != null && marketTicker.startsWith("DEMO-DBPRIMARY-")
            ? FrontendSyntheticData.SOURCE_KIND_DEMO
            : FrontendSyntheticData.SOURCE_KIND_LIVE;
    }

    private static int parseOffset(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("offset must be an integer");
        }
        if (parsed < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        return parsed;
    }

    private static boolean isFeatureOnlyCatalogEntry(MarketMetadata metadata) {
        return metadata != null
            && "indexed".equalsIgnoreCase(metadata.status())
            && metadata.eventTicker() == null
            && metadata.seriesTicker() == null;
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

    private static boolean booleanParam(Map<String, String> params, String name, boolean defaultValue) {
        String raw = params.get(name);
        if (raw == null) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "y", "on" -> true;
            case "0", "false", "no", "n", "off" -> false;
            default -> defaultValue;
        };
    }

    private static int marketSubscriptionCap(Map<String, String> params) {
        String raw = firstNonBlankOrNull(
            params.get("subscription_cap"),
            System.getenv("KALSHI_MARKET_DISCOVERY_MAX_MARKETS")
        );
        if (raw == null) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(raw);
            return Math.max(0, parsed);
        } catch (NumberFormatException e) {
            return 0;
        }
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

    private static Supplier<ReplayDemoStatus> defaultReplayDemoStatusSupplier() {
        return () -> ReplayDemoStatus.disabled(JdbcReplayDemoStatusReader.DEFAULT_REPLAY_ID);
    }

    private static final class AsyncStatusCache<T> {
        private final AtomicReference<CachedStatus<T>> cached = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<T>> refresh = new AtomicReference<>();

        private T get(
            Supplier<T> supplier,
            Function<String, T> fallback,
            ExecutorService executor,
            long timeoutMs,
            long cacheTtlMs
        ) {
            long nowMs = System.currentTimeMillis();
            CachedStatus<T> currentCached = cached.get();
            if (currentCached != null && nowMs - currentCached.updatedAtMs() <= cacheTtlMs) {
                return currentCached.value();
            }
            CompletableFuture<T> currentRefresh = scheduleRefreshIfNeeded(supplier, executor);
            currentCached = cached.get();
            if (currentCached != null) {
                return currentCached.value();
            }
            try {
                T value = currentRefresh.get(timeoutMs, TimeUnit.MILLISECONDS);
                return value == null ? fallback.apply("status_unavailable") : value;
            } catch (TimeoutException e) {
                return fallback.apply("status_timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fallback.apply("status_interrupted");
            } catch (ExecutionException e) {
                return fallback.apply(errorMessage(e.getCause()));
            } catch (RuntimeException e) {
                return fallback.apply(errorMessage(e));
            }
        }

        private CompletableFuture<T> scheduleRefreshIfNeeded(Supplier<T> supplier, ExecutorService executor) {
            CompletableFuture<T> current = refresh.get();
            if (current != null && !current.isDone()) {
                return current;
            }
            CompletableFuture<T> next = new CompletableFuture<>();
            if (!refresh.compareAndSet(current, next)) {
                CompletableFuture<T> raced = refresh.get();
                return raced == null ? next : raced;
            }
            CompletableFuture.supplyAsync(supplier, executor).whenComplete((value, error) -> {
                if (error == null && value != null) {
                    cached.set(new CachedStatus<>(value, System.currentTimeMillis()));
                    next.complete(value);
                } else {
                    next.completeExceptionally(error == null
                        ? new IllegalStateException("status supplier returned null")
                        : error);
                }
                refresh.compareAndSet(next, null);
            });
            return next;
        }
    }

    private record CachedStatus<T>(T value, long updatedAtMs) {
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
