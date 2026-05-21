package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import edu.illinois.group8.backfill.CatalogSyncRequest;
import edu.illinois.group8.backfill.CatalogSyncSummary;
import edu.illinois.group8.backfill.KalshiCatalogSyncService;
import edu.illinois.group8.backfill.KalshiHistoricalBackfillClient;
import edu.illinois.group8.storage.db.JdbcMarketMetadataStore;
import edu.illinois.group8.storage.db.MarketMetadataStore;
import edu.illinois.group8.wrapper.KalshiWrapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

final class CatalogSyncOperatorService implements AutoCloseable {
    static final String STATE_IDLE = "idle";
    static final String STATE_DISABLED = "disabled";
    static final String STATE_RUNNING = "running";
    static final String STATE_COMPLETED = "completed";
    static final String STATE_FAILED = "failed";

    private static final String DEFAULT_KALSHI_BASE_URL = "https://api.elections.kalshi.com";

    private final FrontendAdapterConfig frontendConfig;
    private final Map<String, String> baseEnv;
    private final ExecutorService executor;
    private final Function<CatalogSyncRunConfig, CatalogSyncSummary> runner;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicLong runIds = new AtomicLong();
    private volatile RunSnapshot latest;

    static CatalogSyncOperatorService create(FrontendAdapterConfig config, Map<String, String> env) {
        ExecutorService executor = Executors.newSingleThreadExecutor(daemonThreadFactory("catalog-sync-runner"));
        return new CatalogSyncOperatorService(config, env, executor, CatalogSyncOperatorService::runSync);
    }

    CatalogSyncOperatorService(
        FrontendAdapterConfig frontendConfig,
        Map<String, String> env,
        ExecutorService executor,
        Function<CatalogSyncRunConfig, CatalogSyncSummary> runner
    ) {
        this.frontendConfig = Objects.requireNonNull(frontendConfig, "frontendConfig");
        this.baseEnv = baseEnv(frontendConfig, env == null ? Map.of() : env);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    Map<String, Object> start(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("catalog sync request must be a JSON object");
        }
        if (active.get()) {
            throw new RunAlreadyActiveException();
        }
        long runId = runIds.incrementAndGet();
        CatalogSyncRunConfig runConfig = runConfig(request).validateForRun();
        Instant startedAt = Instant.now();
        RunSnapshot running = RunSnapshot.running(runId, startedAt, redactedConfig(runConfig));
        if (!active.compareAndSet(false, true)) {
            throw new RunAlreadyActiveException();
        }
        latest = running;
        try {
            executor.execute(() -> execute(runId, startedAt, runConfig));
        } catch (RuntimeException e) {
            active.set(false);
            latest = running.failed(Instant.now(), redact(runConfig, e.getMessage()));
            throw e;
        }
        return statusBody();
    }

    Map<String, Object> statusBody() {
        RunSnapshot snapshot = latest;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", snapshot == null
            ? frontendConfig.operatorControlEnabled() ? STATE_IDLE : STATE_DISABLED
            : snapshot.state());
        body.put("running", snapshot != null && STATE_RUNNING.equals(snapshot.state()));
        body.put("operator_control_enabled", frontendConfig.operatorControlEnabled());
        body.put("db_configured", !baseEnv.getOrDefault("CATALOG_SYNC_DB_URL", "").isBlank());
        body.put("kalshi_base_url_configured", !baseEnv.getOrDefault("KALSHI_BASE_URL", "").isBlank());
        body.put("kalshi_key_id_configured", !baseEnv.getOrDefault("KALSHI_KEY_ID", "").isBlank());
        body.put("kalshi_private_key_path_configured", !baseEnv.getOrDefault("KALSHI_KEY_PATH", "").isBlank());
        body.put("kalshi_private_key_pem_configured", !baseEnv.getOrDefault("KALSHI_PRIVATE_KEY", "").isBlank());
        body.put("latest_run", snapshot == null ? null : snapshot.toBody());
        return body;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void execute(long runId, Instant startedAt, CatalogSyncRunConfig runConfig) {
        try {
            CatalogSyncSummary summary = runner.apply(runConfig);
            latest = RunSnapshot.completed(runId, startedAt, Instant.now(), redactedConfig(runConfig), summary);
        } catch (RuntimeException e) {
            latest = RunSnapshot.running(runId, startedAt, redactedConfig(runConfig))
                .failed(Instant.now(), redact(runConfig, e.getMessage()));
        } finally {
            active.set(false);
        }
    }

    private CatalogSyncRunConfig runConfig(JsonNode request) {
        Map<String, String> env = new HashMap<>(baseEnv);
        putBoolean(env, "CATALOG_SYNC_DRY_RUN", request, "dry_run");
        putString(env, "CATALOG_SYNC_SERIES_TICKER", request, "series_ticker");
        putString(env, "CATALOG_SYNC_MARKET_STATUS", request, "market_status");
        if (blank(request, "market_status")) {
            putString(env, "CATALOG_SYNC_MARKET_STATUS", request, "status");
        }
        putInt(env, "CATALOG_SYNC_LIMIT", request, "limit");
        putInt(env, "CATALOG_SYNC_MAX_PAGES", request, "max_pages");
        putInt(env, "CATALOG_SYNC_MAX_TICKERS", request, "max_tickers");
        putString(env, "CATALOG_SYNC_MVE_FILTER", request, "mve_filter");

        CatalogSyncRequest syncRequest = new CatalogSyncRequest(
            booleanValue(env, "CATALOG_SYNC_DRY_RUN", true),
            env.getOrDefault("CATALOG_SYNC_SERIES_TICKER", ""),
            env.getOrDefault("CATALOG_SYNC_MARKET_STATUS", "open"),
            positiveInt(env, "CATALOG_SYNC_LIMIT", 1000),
            positiveInt(env, "CATALOG_SYNC_MAX_PAGES", 1),
            nonNegativeInt(env, "CATALOG_SYNC_MAX_TICKERS", 0),
            env.getOrDefault("CATALOG_SYNC_MVE_FILTER", "")
        );
        return new CatalogSyncRunConfig(
            syncRequest,
            env.getOrDefault("KALSHI_BASE_URL", DEFAULT_KALSHI_BASE_URL),
            env.getOrDefault("KALSHI_KEY_ID", ""),
            env.getOrDefault("KALSHI_KEY_PATH", ""),
            env.getOrDefault("KALSHI_PRIVATE_KEY", ""),
            env.getOrDefault("CATALOG_SYNC_DB_URL", ""),
            env.getOrDefault("CATALOG_SYNC_DB_USER", ""),
            env.getOrDefault("CATALOG_SYNC_DB_PASSWORD", "")
        );
    }

    private Map<String, Object> redactedConfig(CatalogSyncRunConfig runConfig) {
        CatalogSyncRequest request = runConfig.request();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dry_run", request.dryRun());
        body.put("series_ticker", emptyToNull(request.seriesTicker()));
        body.put("market_status", emptyToNull(request.marketStatus()));
        body.put("limit", request.limit());
        body.put("max_pages", request.maxPages());
        body.put("max_tickers", request.maxTickers());
        body.put("mve_filter", emptyToNull(request.mveFilter()));
        body.put("kalshi_base_url", OperatorRedactor.redact(runConfig.kalshiBaseUrl()));
        body.put("kalshi_key_id_configured", !runConfig.kalshiKeyId().isBlank());
        body.put("kalshi_private_key_path_configured", !runConfig.kalshiKeyPath().isBlank());
        body.put("kalshi_private_key_pem_configured", !runConfig.kalshiPrivateKeyPem().isBlank());
        body.put("db_configured", !runConfig.dbUrl().isBlank());
        return body;
    }

    private String redact(CatalogSyncRunConfig runConfig, String message) {
        String redacted = message == null ? "" : message;
        if (!runConfig.kalshiKeyId().isBlank()) {
            redacted = redacted.replace(runConfig.kalshiKeyId(), "[redacted]");
        }
        if (!runConfig.kalshiKeyPath().isBlank()) {
            redacted = redacted.replace(runConfig.kalshiKeyPath(), "[redacted]");
        }
        if (!runConfig.kalshiPrivateKeyPem().isBlank()) {
            redacted = redacted.replace(runConfig.kalshiPrivateKeyPem(), "[redacted]");
        }
        if (!runConfig.dbPassword().isBlank()) {
            redacted = redacted.replace(runConfig.dbPassword(), "[redacted]");
        }
        return OperatorRedactor.redact(redacted);
    }

    private static CatalogSyncSummary runSync(CatalogSyncRunConfig config) {
        KalshiWrapper wrapper = new KalshiWrapper(
            config.kalshiBaseUrl(),
            config.kalshiKeyId(),
            config.kalshiKeyPath(),
            config.kalshiPrivateKeyPem()
        );
        MarketMetadataStore store = config.request().dryRun()
            ? null
            : JdbcMarketMetadataStore.fromDriverManager(config.dbUrl(), config.dbUser(), config.dbPassword());
        return new KalshiCatalogSyncService(new KalshiHistoricalBackfillClient(wrapper), store).run(config.request());
    }

    private static Map<String, String> baseEnv(FrontendAdapterConfig config, Map<String, String> env) {
        Map<String, String> merged = new HashMap<>(env);
        putDefault(merged, "KALSHI_BASE_URL", DEFAULT_KALSHI_BASE_URL);
        putDefault(merged, "CATALOG_SYNC_DB_URL", config.dbUrl());
        putDefault(merged, "CATALOG_SYNC_DB_USER", config.dbUser());
        putDefault(merged, "CATALOG_SYNC_DB_PASSWORD", config.dbPassword());
        return Map.copyOf(merged);
    }

    private static void putDefault(Map<String, String> env, String key, String value) {
        if (env.getOrDefault(key, "").isBlank() && value != null && !value.isBlank()) {
            env.put(key, value);
        }
    }

    private static void putString(Map<String, String> env, String key, JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node != null && !node.isNull()) {
            String value = node.asText("").trim();
            if (!value.isBlank()) {
                env.put(key, value);
            }
        }
    }

    private static void putBoolean(Map<String, String> env, String key, JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node != null && !node.isNull()) {
            env.put(key, Boolean.toString(node.asBoolean()));
        }
    }

    private static void putInt(Map<String, String> env, String key, JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node != null && !node.isNull()) {
            try {
                env.put(key, Integer.toString(Integer.parseInt(node.asText().trim())));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
        }
    }

    private static boolean blank(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node == null || node.isNull() || node.asText("").trim().isBlank();
    }

    private static boolean booleanValue(Map<String, String> env, String key, boolean defaultValue) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "y", "on" -> true;
            default -> false;
        };
    }

    private static int positiveInt(Map<String, String> env, String key, int defaultValue) {
        int value = intValue(env, key, defaultValue);
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static int nonNegativeInt(Map<String, String> env, String key, int defaultValue) {
        int value = intValue(env, key, defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be zero or positive");
        }
        return value;
    }

    private static int intValue(Map<String, String> env, String key, int defaultValue) {
        String raw = env.get(key);
        return raw == null || raw.isBlank() ? defaultValue : Integer.parseInt(raw.trim());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicLong sequence = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    static final class RunAlreadyActiveException extends IllegalStateException {
        RunAlreadyActiveException() {
            super("catalog sync run is already active");
        }
    }

    record CatalogSyncRunConfig(
        CatalogSyncRequest request,
        String kalshiBaseUrl,
        String kalshiKeyId,
        String kalshiKeyPath,
        String kalshiPrivateKeyPem,
        String dbUrl,
        String dbUser,
        String dbPassword
    ) {
        CatalogSyncRunConfig {
            Objects.requireNonNull(request, "request");
            kalshiBaseUrl = kalshiBaseUrl == null ? "" : kalshiBaseUrl.trim();
            kalshiKeyId = kalshiKeyId == null ? "" : kalshiKeyId.trim();
            kalshiKeyPath = kalshiKeyPath == null ? "" : kalshiKeyPath.trim();
            kalshiPrivateKeyPem = kalshiPrivateKeyPem == null ? "" : kalshiPrivateKeyPem.trim();
            dbUrl = dbUrl == null ? "" : dbUrl.trim();
            dbUser = dbUser == null ? "" : dbUser.trim();
            dbPassword = dbPassword == null ? "" : dbPassword;
        }

        private CatalogSyncRunConfig validateForRun() {
            if (kalshiBaseUrl.isBlank()) {
                throw new IllegalArgumentException("KALSHI_BASE_URL is required for catalog sync");
            }
            if (!request.dryRun() && dbUrl.isBlank()) {
                throw new IllegalArgumentException(
                    "FRONTEND_ADAPTER_DB_URL or DB_WRITER_DATABASE_URL is required when dry_run=false"
                );
            }
            return this;
        }
    }

    private record RunSnapshot(
        long runId,
        String state,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> redactedConfig,
        CatalogSyncSummary summary,
        String error
    ) {
        private static RunSnapshot running(long runId, Instant startedAt, Map<String, Object> redactedConfig) {
            return new RunSnapshot(runId, STATE_RUNNING, startedAt, null, new LinkedHashMap<>(redactedConfig), null, null);
        }

        private static RunSnapshot completed(
            long runId,
            Instant startedAt,
            Instant finishedAt,
            Map<String, Object> redactedConfig,
            CatalogSyncSummary summary
        ) {
            return new RunSnapshot(runId, STATE_COMPLETED, startedAt, finishedAt, new LinkedHashMap<>(redactedConfig),
                summary, null);
        }

        private RunSnapshot failed(Instant finishedAt, String error) {
            return new RunSnapshot(runId, STATE_FAILED, startedAt, finishedAt, redactedConfig, null, error);
        }

        private Map<String, Object> toBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("run_id", runId);
            body.put("state", state);
            body.put("started_at", startedAt == null ? null : startedAt.toString());
            body.put("finished_at", finishedAt == null ? null : finishedAt.toString());
            body.put("config", redactedConfig);
            body.put("summary", summary == null ? null : summaryBody(summary));
            body.put("last_error", error);
            return body;
        }

        private static Map<String, Object> summaryBody(CatalogSyncSummary summary) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("pages_fetched", summary.pagesFetched());
            body.put("markets_discovered", summary.marketsDiscovered());
            body.put("markets_selected", summary.marketsSelected());
            body.put("rows_upserted", summary.rowsUpserted());
            body.put("dry_run_rows", summary.dryRunRows());
            body.put("failures", summary.failures());
            body.put("has_more", summary.hasMore());
            body.put("next_cursor_present", !summary.nextCursor().isBlank());
            body.put("outcome", summary.failures() > 0 ? "failed" : "completed");
            return body;
        }
    }
}
