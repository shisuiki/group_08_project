package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import edu.illinois.group8.semantic.HttpOpenRouterClient;
import edu.illinois.group8.semantic.OpenRouterClient;
import edu.illinois.group8.semantic.SemanticMetadataBatchService;
import edu.illinois.group8.semantic.SemanticMetadataBatchSummary;
import edu.illinois.group8.semantic.SemanticMetadataConfig;
import edu.illinois.group8.storage.db.JdbcMarketMetadataReader;
import edu.illinois.group8.storage.db.JdbcMarketSemanticMetadataStore;
import edu.illinois.group8.storage.db.MarketMetadataReader;
import edu.illinois.group8.storage.db.MarketSemanticMetadataStore;

import java.math.BigDecimal;
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

final class SemanticMetadataOperatorService implements AutoCloseable {
    static final String STATE_IDLE = "idle";
    static final String STATE_DISABLED = "disabled";
    static final String STATE_RUNNING = "running";
    static final String STATE_COMPLETED = "completed";
    static final String STATE_FAILED = "failed";

    private final FrontendAdapterConfig frontendConfig;
    private final Map<String, String> baseEnv;
    private final ExecutorService executor;
    private final Function<SemanticMetadataConfig, SemanticMetadataBatchSummary> runner;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicLong runIds = new AtomicLong();
    private volatile RunSnapshot latest;

    static SemanticMetadataOperatorService create(FrontendAdapterConfig config, Map<String, String> env) {
        ExecutorService executor = Executors.newSingleThreadExecutor(daemonThreadFactory("semantic-metadata-runner"));
        return new SemanticMetadataOperatorService(config, env, executor, SemanticMetadataOperatorService::runBatch);
    }

    SemanticMetadataOperatorService(
        FrontendAdapterConfig frontendConfig,
        Map<String, String> env,
        ExecutorService executor,
        Function<SemanticMetadataConfig, SemanticMetadataBatchSummary> runner
    ) {
        this.frontendConfig = Objects.requireNonNull(frontendConfig, "frontendConfig");
        this.baseEnv = baseEnv(frontendConfig, env == null ? Map.of() : env);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    Map<String, Object> start(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("semantic metadata run request must be a JSON object");
        }
        if (active.get()) {
            throw new RunAlreadyActiveException();
        }
        long runId = runIds.incrementAndGet();
        RunConfig runConfig = runConfig(request);
        SemanticMetadataConfig config = runConfig.config().validateForRun();
        Instant startedAt = Instant.now();
        RunSnapshot running = RunSnapshot.running(runId, startedAt, redactedConfig(config, runConfig));
        if (!active.compareAndSet(false, true)) {
            throw new RunAlreadyActiveException();
        }
        latest = running;
        try {
            executor.execute(() -> execute(runId, startedAt, config, runConfig));
        } catch (RuntimeException e) {
            active.set(false);
            latest = running.failed(Instant.now(), redact(config, e.getMessage()));
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
        body.put("db_configured", !baseEnv.getOrDefault("LLM_METADATA_DB_URL", "").isBlank());
        body.put("openrouter_api_key_configured", !baseEnv.getOrDefault("OPENROUTER_API_KEY", "").isBlank()
            || !baseEnv.getOrDefault("OPENROUTER_API_KEY_FILE", "").isBlank());
        body.put("latest_run", snapshot == null ? null : snapshot.toBody());
        return body;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void execute(
        long runId,
        Instant startedAt,
        SemanticMetadataConfig config,
        RunConfig runConfig
    ) {
        try {
            SemanticMetadataBatchSummary summary = runner.apply(config);
            latest = RunSnapshot.completed(
                runId,
                startedAt,
                Instant.now(),
                redactedConfig(config, runConfig),
                summary
            );
        } catch (RuntimeException e) {
            latest = RunSnapshot.running(runId, startedAt, redactedConfig(config, runConfig))
                .failed(Instant.now(), redact(config, e.getMessage()));
        } finally {
            active.set(false);
        }
    }

    private RunConfig runConfig(JsonNode request) {
        Map<String, String> env = new HashMap<>(baseEnv);
        putBoolean(env, "LLM_METADATA_DRY_RUN", request, "dry_run");
        putBoolean(env, "LLM_METADATA_OVERWRITE", request, "overwrite");
        putInt(env, "LLM_METADATA_MAX_MARKETS", request, "max_markets");
        putInt(env, "LLM_METADATA_MAX_RETRIES", request, "max_retries");
        putInt(env, "LLM_METADATA_MAX_TOKENS", request, "max_tokens");
        putString(env, "LLM_METADATA_MARKET_TICKER", request, "market_ticker");
        putString(env, "LLM_METADATA_SERIES_TICKER", request, "series_ticker");
        putString(env, "LLM_METADATA_MARKET_STATUS", request, "market_status");
        if (blank(request, "market_status")) {
            putString(env, "LLM_METADATA_MARKET_STATUS", request, "status");
        }
        putString(env, "LLM_METADATA_TAXONOMY_VERSION", request, "taxonomy_version");
        putString(env, "LLM_METADATA_MODEL", request, "model");
        putString(env, "LLM_METADATA_FALLBACK_MODEL", request, "fallback_model");
        putDecimal(env, "LLM_METADATA_BUDGET_USD", request, "budget_usd");
        putDecimal(env, "LLM_METADATA_ESTIMATED_PAID_REQUEST_COST_USD", request, "estimated_paid_request_cost_usd");
        putString(env, "OPENROUTER_API_KEY", request, "openrouter_api_key");

        boolean allowPaidFallback = booleanField(request, "allow_paid_fallback", true);
        if (!allowPaidFallback) {
            String fallbackModel = env.getOrDefault("LLM_METADATA_FALLBACK_MODEL", "");
            if (!fallbackModel.isBlank() && !fallbackModel.contains(":free")) {
                env.put("LLM_METADATA_FALLBACK_MODEL", env.getOrDefault("LLM_METADATA_MODEL", fallbackModel));
            }
        }
        return new RunConfig(SemanticMetadataConfig.from(env), allowPaidFallback, !blank(request, "openrouter_api_key"));
    }

    private Map<String, Object> redactedConfig(SemanticMetadataConfig config, RunConfig runConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dry_run", config.dryRun());
        body.put("eligible_only", config.marketTicker().isBlank());
        body.put("overwrite", config.overwrite());
        body.put("max_markets", config.maxMarkets());
        body.put("max_retries", config.maxRetries());
        body.put("max_tokens", config.maxTokens());
        body.put("market_ticker", emptyToNull(config.marketTicker()));
        body.put("series_ticker", emptyToNull(config.seriesTicker()));
        body.put("market_status", emptyToNull(config.marketStatus()));
        body.put("taxonomy_version", config.taxonomyVersion());
        body.put("model", config.model());
        body.put("fallback_model", emptyToNull(config.fallbackModel()));
        body.put("allow_paid_fallback", runConfig.allowPaidFallback());
        body.put("budget_usd", config.budgetUsd().toPlainString());
        body.put("estimated_paid_request_cost_usd", config.estimatedPaidRequestCostUsd().toPlainString());
        body.put("db_configured", !config.dbUrl().isBlank());
        body.put("openrouter_api_key_configured", !config.openRouterApiKey().isBlank());
        body.put("openrouter_api_key_request_provided", runConfig.requestKeyProvided());
        return body;
    }

    private String redact(SemanticMetadataConfig config, String message) {
        String redacted = message == null ? "" : message;
        if (!config.openRouterApiKey().isBlank()) {
            redacted = redacted.replace(config.openRouterApiKey(), "[redacted]");
        }
        return OperatorRedactor.redact(redacted);
    }

    private static SemanticMetadataBatchSummary runBatch(SemanticMetadataConfig config) {
        MarketMetadataReader marketReader = JdbcMarketMetadataReader.fromDriverManager(
            config.dbUrl(),
            config.dbUser(),
            config.dbPassword()
        );
        MarketSemanticMetadataStore store = JdbcMarketSemanticMetadataStore.fromDriverManager(
            config.dbUrl(),
            config.dbUser(),
            config.dbPassword()
        );
        OpenRouterClient client = config.dryRun()
            ? (model, messages, maxTokens) -> {
                throw new IllegalStateException("dry-run should not call OpenRouter");
            }
            : new HttpOpenRouterClient(config);
        return new SemanticMetadataBatchService(marketReader, store, client).run(config);
    }

    private static Map<String, String> baseEnv(FrontendAdapterConfig config, Map<String, String> env) {
        Map<String, String> merged = new HashMap<>(env);
        putDefault(merged, "LLM_METADATA_DB_URL", config.dbUrl());
        putDefault(merged, "LLM_METADATA_DB_USER", config.dbUser());
        putDefault(merged, "LLM_METADATA_DB_PASSWORD", config.dbPassword());
        putDefault(merged, "LLM_METADATA_MODEL", config.llmMetadataModel());
        putDefault(merged, "LLM_METADATA_FALLBACK_MODEL", config.llmMetadataFallbackModel());
        putDefault(merged, "LLM_METADATA_TAXONOMY_VERSION", config.llmMetadataTaxonomyVersion());
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

    private static void putDecimal(Map<String, String> env, String key, JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node != null && !node.isNull()) {
            try {
                env.put(key, new BigDecimal(node.asText()).toPlainString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " must be decimal");
            }
        }
    }

    private static boolean booleanField(JsonNode request, String field, boolean defaultValue) {
        JsonNode node = request.get(field);
        return node == null || node.isNull() ? defaultValue : node.asBoolean(defaultValue);
    }

    private static boolean blank(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node == null || node.isNull() || node.asText("").trim().isBlank();
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
            super("semantic metadata run is already active");
        }
    }

    private record RunConfig(
        SemanticMetadataConfig config,
        boolean allowPaidFallback,
        boolean requestKeyProvided
    ) {
    }

    private record RunSnapshot(
        long runId,
        String state,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> redactedConfig,
        SemanticMetadataBatchSummary summary,
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
            SemanticMetadataBatchSummary summary
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

        private static Map<String, Object> summaryBody(SemanticMetadataBatchSummary summary) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("processed", summary.processed());
            body.put("generated", summary.generated());
            body.put("review_required", summary.reviewRequired());
            body.put("rate_limited", summary.rateLimited());
            body.put("failed", summary.failed());
            body.put("skipped", summary.skipped());
            body.put("paid_requests", summary.paidRequests());
            body.put("estimated_spend_usd", summary.estimatedSpendUsd().toPlainString());
            body.put("primary_model", summary.primaryModel());
            body.put("fallback_model", summary.fallbackModel());
            body.put("outcome", outcome(summary));
            if (summary.reviewRequired() > 0) {
                body.put("review_required_hint",
                    "Inspect market_semantic_metadata.error and raw_response; parse failures may need higher max_tokens.");
            }
            return body;
        }

        private static String outcome(SemanticMetadataBatchSummary summary) {
            if (summary.rateLimited() > 0) {
                return "rate_limited";
            }
            if (summary.failed() > 0) {
                return "failed";
            }
            if (summary.reviewRequired() > 0) {
                return "review_required";
            }
            if (summary.processed() == 0 && summary.skipped() > 0) {
                return "skipped";
            }
            return "completed";
        }
    }
}
