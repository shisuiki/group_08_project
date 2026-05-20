package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

final class DemoOrchestratorService implements AutoCloseable {
    static final String STATE_IDLE = "idle";
    static final String STATE_DISABLED = "disabled";
    static final String STATE_RUNNING = "running";
    static final String STATE_COMPLETED = "completed";
    static final String STATE_FAILED = "failed";

    private static final int DEFAULT_CATALOG_LIMIT = 20;
    private static final int DEFAULT_CATALOG_MAX_PAGES = 1;
    private static final int DEFAULT_CATALOG_MAX_TICKERS = 5;
    private static final int DEFAULT_SEMANTIC_MAX_MARKETS = 5;
    private static final int DEFAULT_SEMANTIC_MAX_TOKENS = 2200;
    private static final int DEFAULT_SEMANTIC_MAX_RETRIES = 2;
    private static final Set<String> FORBIDDEN_PAYLOAD_FIELDS = Set.of(
        "cmd",
        "command",
        "commands",
        "env",
        "environment",
        "openrouter_api_key",
        "path",
        "process",
        "script",
        "shell"
    );

    private final FrontendAdapterConfig config;
    private final FrontendReleaseInfo releaseInfo;
    private final CatalogSyncOperatorService catalogSyncOperator;
    private final SemanticMetadataOperatorService semanticMetadataOperator;
    private final Supplier<Map<String, Object>> productStatusSnapshot;
    private final ExecutorService executor;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicLong runIds = new AtomicLong();
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();
    private volatile RunSnapshot latest;

    static DemoOrchestratorService create(
        FrontendAdapterConfig config,
        FrontendReleaseInfo releaseInfo,
        CatalogSyncOperatorService catalogSyncOperator,
        SemanticMetadataOperatorService semanticMetadataOperator,
        Supplier<Map<String, Object>> productStatusSnapshot
    ) {
        ExecutorService executor = Executors.newSingleThreadExecutor(daemonThreadFactory("demo-orchestrator-runner"));
        return new DemoOrchestratorService(
            config,
            releaseInfo,
            catalogSyncOperator,
            semanticMetadataOperator,
            productStatusSnapshot,
            executor
        );
    }

    DemoOrchestratorService(
        FrontendAdapterConfig config,
        FrontendReleaseInfo releaseInfo,
        CatalogSyncOperatorService catalogSyncOperator,
        SemanticMetadataOperatorService semanticMetadataOperator,
        Supplier<Map<String, Object>> productStatusSnapshot,
        ExecutorService executor
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.releaseInfo = releaseInfo == null ? FrontendReleaseInfo.empty() : releaseInfo;
        this.catalogSyncOperator = Objects.requireNonNull(catalogSyncOperator, "catalogSyncOperator");
        this.semanticMetadataOperator = Objects.requireNonNull(semanticMetadataOperator, "semanticMetadataOperator");
        this.productStatusSnapshot = Objects.requireNonNull(productStatusSnapshot, "productStatusSnapshot");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    Map<String, Object> start(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("demo orchestrator request must be a JSON object");
        }
        rejectCommandPayload(request);
        if (active.get()) {
            throw new RunAlreadyActiveException();
        }
        RunConfig runConfig = runConfig(request);
        long runId = runIds.incrementAndGet();
        Instant startedAt = Instant.now();
        RunSnapshot running = RunSnapshot.running(runId, startedAt, runConfig.action(), runConfig.mode(),
            runConfig.redactedConfig(), releaseInfo, dataSourceBody());
        if (!active.compareAndSet(false, true)) {
            throw new RunAlreadyActiveException();
        }
        latest = running;
        try {
            executor.execute(() -> execute(runId, startedAt, runConfig));
        } catch (RuntimeException e) {
            active.set(false);
            latest = running.failed(Instant.now(), OperatorRedactor.redact(e.getMessage()));
            throw e;
        }
        return statusBody();
    }

    Map<String, Object> statusBody() {
        RunSnapshot snapshot = latest;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", snapshot == null
            ? config.operatorControlEnabled() ? STATE_IDLE : STATE_DISABLED
            : snapshot.state());
        body.put("running", snapshot != null && STATE_RUNNING.equals(snapshot.state()));
        body.put("operator_control_enabled", config.operatorControlEnabled());
        body.put("actions", Action.publicNames());
        body.put("safe_defaults", safeDefaultsBody());
        body.put("release", releaseInfo.toBody());
        body.put("data_source", dataSourceBody());
        body.put("latest_run", snapshot == null ? null : snapshot.toBody());
        return body;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void execute(long runId, Instant startedAt, RunConfig runConfig) {
        try {
            Map<String, Object> summary = runAction(runConfig);
            latest = RunSnapshot.completed(runId, startedAt, Instant.now(), runConfig.action(), runConfig.mode(),
                runConfig.redactedConfig(), summary, releaseInfo, dataSourceBody());
        } catch (RuntimeException e) {
            latest = RunSnapshot.running(runId, startedAt, runConfig.action(), runConfig.mode(),
                    runConfig.redactedConfig(), releaseInfo, dataSourceBody())
                .failed(Instant.now(), OperatorRedactor.redact(e.getMessage()));
        } finally {
            active.set(false);
        }
    }

    private Map<String, Object> runAction(RunConfig runConfig) {
        return switch (runConfig.action()) {
            case PRODUCT_READINESS_CHECK, REPLAY_DEMO_CHECK -> productReadinessSummary(runConfig);
            case LIVE_PRODUCT_CHECK -> liveProductSummary(runConfig);
            case CATALOG_STATUS -> catalogStatusSummary(runConfig);
            case CATALOG_SYNC_DRY_RUN -> catalogDryRunSummary(runConfig);
            case SEMANTIC_METADATA_DRY_RUN -> semanticDryRunSummary(runConfig);
            case CATALOG_AND_SEMANTIC_DRY_RUN -> catalogAndSemanticDryRunSummary(runConfig);
        };
    }

    private Map<String, Object> productReadinessSummary(RunConfig runConfig) {
        Map<String, Object> snapshot = productStatusSnapshot.get();
        Map<String, Object> body = baseSummary(runConfig, "read product readiness and quote/latency evidence");
        body.put("status_snapshot", snapshot);
        return body;
    }

    private Map<String, Object> liveProductSummary(RunConfig runConfig) {
        if (!runConfig.confirmLive()) {
            throw new IllegalArgumentException("live product demo action requires confirm_live=true");
        }
        Map<String, Object> body = productReadinessSummary(runConfig);
        body.put("stdout_summary", "confirmed live-product readiness check; no live mutation executed");
        return body;
    }

    private Map<String, Object> catalogStatusSummary(RunConfig runConfig) {
        Map<String, Object> body = baseSummary(runConfig, "read catalog sync status");
        body.put("catalog_sync", catalogSyncOperator.statusBody());
        return body;
    }

    private Map<String, Object> catalogDryRunSummary(RunConfig runConfig) {
        Map<String, Object> started = catalogSyncOperator.start(runConfig.catalogRequest());
        Map<String, Object> body = baseSummary(runConfig, "scheduled catalog sync dry-run");
        body.put("catalog_sync", started);
        return body;
    }

    private Map<String, Object> semanticDryRunSummary(RunConfig runConfig) {
        Map<String, Object> started = semanticMetadataOperator.start(runConfig.semanticRequest());
        Map<String, Object> body = baseSummary(runConfig, "scheduled semantic metadata dry-run");
        body.put("semantic_metadata", started);
        return body;
    }

    private Map<String, Object> catalogAndSemanticDryRunSummary(RunConfig runConfig) {
        Map<String, Object> catalog = catalogSyncOperator.start(runConfig.catalogRequest());
        Map<String, Object> semantic = semanticMetadataOperator.start(runConfig.semanticRequest());
        Map<String, Object> body = baseSummary(runConfig, "scheduled catalog sync and semantic metadata dry-runs");
        body.put("catalog_sync", catalog);
        body.put("semantic_metadata", semantic);
        return body;
    }

    private Map<String, Object> baseSummary(RunConfig runConfig, String stdoutSummary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", runConfig.mode());
        body.put("action", runConfig.action().publicName());
        body.put("stdout_summary", stdoutSummary);
        body.put("evidence_url", primaryEvidenceUrl(runConfig.action()));
        body.put("evidence_urls", evidenceUrls(runConfig.action()));
        body.put("release", releaseInfo.toBody());
        body.put("data_source", dataSourceBody());
        return body;
    }

    private RunConfig runConfig(JsonNode request) {
        Action action = Action.from(request.path("action").asText(""));
        boolean confirmLive = booleanField(request, "confirm_live", false)
            || booleanField(request, "confirm", false);
        if (action == Action.LIVE_PRODUCT_CHECK && !confirmLive) {
            throw new IllegalArgumentException("live product demo action requires confirm_live=true");
        }
        Map<String, Object> redacted = redactedConfig(action, confirmLive);
        return new RunConfig(action, action.mode(), confirmLive, catalogRequest(request), semanticRequest(request), redacted);
    }

    private ObjectNode catalogRequest(JsonNode request) {
        JsonNode catalog = objectField(request, "catalog");
        ObjectNode body = mapper.createObjectNode();
        body.put("dry_run", true);
        body.put("limit", positiveInt(catalog, "limit", DEFAULT_CATALOG_LIMIT));
        body.put("max_pages", positiveInt(catalog, "max_pages", DEFAULT_CATALOG_MAX_PAGES));
        body.put("max_tickers", nonNegativeInt(catalog, "max_tickers", DEFAULT_CATALOG_MAX_TICKERS));
        putText(body, catalog, "series_ticker");
        putText(body, catalog, "market_status");
        if (!catalog.hasNonNull("market_status")) {
            putText(body, catalog, "status", "market_status");
        }
        putText(body, catalog, "mve_filter");
        return body;
    }

    private ObjectNode semanticRequest(JsonNode request) {
        JsonNode semantic = objectField(request, "semantic");
        ObjectNode body = mapper.createObjectNode();
        body.put("dry_run", true);
        body.put("overwrite", false);
        body.put("allow_paid_fallback", false);
        body.put("max_markets", positiveInt(semantic, "max_markets", DEFAULT_SEMANTIC_MAX_MARKETS));
        body.put("max_tokens", positiveInt(semantic, "max_tokens", DEFAULT_SEMANTIC_MAX_TOKENS));
        body.put("max_retries", nonNegativeInt(semantic, "max_retries", DEFAULT_SEMANTIC_MAX_RETRIES));
        body.put("market_status", textField(semantic, "market_status", "active"));
        putText(body, semantic, "market_ticker");
        putText(body, semantic, "series_ticker");
        putText(body, semantic, "taxonomy_version");
        putText(body, semantic, "model");
        putText(body, semantic, "fallback_model");
        putText(body, semantic, "budget_usd");
        putText(body, semantic, "estimated_paid_request_cost_usd");
        return body;
    }

    private Map<String, Object> redactedConfig(Action action, boolean confirmLive) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action.publicName());
        body.put("mode", action.mode());
        body.put("confirm_live", confirmLive);
        body.put("llm_dry_run", true);
        body.put("catalog_dry_run", true);
        body.put("allow_paid_fallback", false);
        body.put("release_sha", emptyToNull(releaseInfo.sha()));
        body.put("release_profile", emptyToNull(releaseInfo.profile()));
        body.put("data_source", dataSourceBody());
        return body;
    }

    private Map<String, Object> dataSourceBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_mode", config.sourceMode().name().toLowerCase(Locale.ROOT));
        body.put("feature_source", config.featureSource().name().toLowerCase(Locale.ROOT));
        body.put("metadata_source", config.metadataSource().name().toLowerCase(Locale.ROOT));
        body.put("db_configured", !config.dbUrl().isBlank());
        return body;
    }

    private Map<String, Object> safeDefaultsBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("llm_dry_run_default", true);
        body.put("catalog_dry_run_default", true);
        body.put("live_action_requires_confirm", true);
        body.put("shell_execution_allowed", false);
        body.put("semantic_max_markets", DEFAULT_SEMANTIC_MAX_MARKETS);
        body.put("catalog_max_pages", DEFAULT_CATALOG_MAX_PAGES);
        return body;
    }

    private String primaryEvidenceUrl(Action action) {
        return evidenceUrls(action).get(0);
    }

    private List<String> evidenceUrls(Action action) {
        return switch (action) {
            case CATALOG_STATUS, CATALOG_SYNC_DRY_RUN -> List.of(
                "/operator/catalog/sync-status",
                "/markets?limit=20"
            );
            case SEMANTIC_METADATA_DRY_RUN -> List.of(
                "/operator/semantic-metadata/run-status",
                "/api/semantic-metadata/treemap?group_by=sector&limit=50",
                "/api/semantic-metadata/markets?limit=50"
            );
            case CATALOG_AND_SEMANTIC_DRY_RUN -> List.of(
                "/operator/catalog/sync-status",
                "/operator/semantic-metadata/run-status",
                "/api/semantic-metadata/treemap?group_by=sector&limit=50"
            );
            default -> List.of(
                "/health",
                "/quotes",
                "/quotes/updates",
                "/ops/pipeline",
                "/ops/latency"
            );
        };
    }

    private void rejectCommandPayload(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(field -> {
                String normalized = field.trim().toLowerCase(Locale.ROOT);
                if (FORBIDDEN_PAYLOAD_FIELDS.contains(normalized)) {
                    throw new IllegalArgumentException("demo orchestrator does not accept shell/env/secret payload fields");
                }
                rejectCommandPayload(node.get(field));
            });
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                rejectCommandPayload(child);
            }
        }
    }

    private static JsonNode objectField(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return value;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        return value;
    }

    private static boolean booleanField(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asBoolean(defaultValue);
    }

    private static int positiveInt(JsonNode node, String field, int defaultValue) {
        int value = intValue(node, field, defaultValue);
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static int nonNegativeInt(JsonNode node, String field, int defaultValue) {
        int value = intValue(node, field, defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be zero or positive");
        }
        return value;
    }

    private static int intValue(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText("").isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static String textField(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText("").trim().isBlank()) {
            return defaultValue;
        }
        return value.asText().trim();
    }

    private static void putText(ObjectNode target, JsonNode source, String field) {
        putText(target, source, field, field);
    }

    private static void putText(ObjectNode target, JsonNode source, String field, String targetField) {
        JsonNode value = source.path(field);
        if (!value.isMissingNode() && !value.isNull()) {
            String text = value.asText("").trim();
            if (!text.isBlank()) {
                target.put(targetField, text);
            }
        }
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
            super("demo orchestrator run is already active");
        }
    }

    private enum Action {
        PRODUCT_READINESS_CHECK("product_readiness_check", "product"),
        REPLAY_DEMO_CHECK("replay_demo_check", "replay_demo"),
        LIVE_PRODUCT_CHECK("live_product_check", "live_product"),
        CATALOG_STATUS("catalog_status", "catalog"),
        CATALOG_SYNC_DRY_RUN("catalog_sync_dry_run", "catalog"),
        SEMANTIC_METADATA_DRY_RUN("semantic_metadata_dry_run", "semantic_metadata"),
        CATALOG_AND_SEMANTIC_DRY_RUN("catalog_and_semantic_dry_run", "catalog_semantic");

        private final String publicName;
        private final String mode;

        Action(String publicName, String mode) {
            this.publicName = publicName;
            this.mode = mode;
        }

        private String publicName() {
            return publicName;
        }

        private String mode() {
            return mode;
        }

        private static Action from(String raw) {
            String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (Action action : values()) {
                if (action.publicName.equals(normalized)) {
                    return action;
                }
            }
            throw new IllegalArgumentException("unsupported demo orchestrator action: " + raw);
        }

        private static List<String> publicNames() {
            List<String> names = new ArrayList<>();
            for (Action action : values()) {
                names.add(action.publicName);
            }
            return names;
        }
    }

    private record RunConfig(
        Action action,
        String mode,
        boolean confirmLive,
        JsonNode catalogRequest,
        JsonNode semanticRequest,
        Map<String, Object> redactedConfig
    ) {
        private RunConfig {
            redactedConfig = new LinkedHashMap<>(redactedConfig);
        }
    }

    private record RunSnapshot(
        long runId,
        String state,
        String action,
        String mode,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> redactedConfig,
        Map<String, Object> summary,
        FrontendReleaseInfo releaseInfo,
        Map<String, Object> dataSource,
        String error
    ) {
        private static RunSnapshot running(
            long runId,
            Instant startedAt,
            Action action,
            String mode,
            Map<String, Object> redactedConfig,
            FrontendReleaseInfo releaseInfo,
            Map<String, Object> dataSource
        ) {
            return new RunSnapshot(runId, STATE_RUNNING, action.publicName(), mode, startedAt, null,
                new LinkedHashMap<>(redactedConfig), null, releaseInfo, new LinkedHashMap<>(dataSource), null);
        }

        private static RunSnapshot completed(
            long runId,
            Instant startedAt,
            Instant finishedAt,
            Action action,
            String mode,
            Map<String, Object> redactedConfig,
            Map<String, Object> summary,
            FrontendReleaseInfo releaseInfo,
            Map<String, Object> dataSource
        ) {
            return new RunSnapshot(runId, STATE_COMPLETED, action.publicName(), mode, startedAt, finishedAt,
                new LinkedHashMap<>(redactedConfig), new LinkedHashMap<>(summary), releaseInfo,
                new LinkedHashMap<>(dataSource), null);
        }

        private RunSnapshot failed(Instant finishedAt, String error) {
            return new RunSnapshot(runId, STATE_FAILED, action, mode, startedAt, finishedAt, redactedConfig, summary,
                releaseInfo, dataSource, error);
        }

        private Map<String, Object> toBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("run_id", runId);
            body.put("state", state);
            body.put("action", action);
            body.put("mode", mode);
            body.put("started_at", startedAt == null ? null : startedAt.toString());
            body.put("finished_at", finishedAt == null ? null : finishedAt.toString());
            body.put("config", redactedConfig);
            body.put("summary", summary);
            body.put("stdout_summary", summary == null ? null : summary.get("stdout_summary"));
            body.put("evidence_url", summary == null ? null : summary.get("evidence_url"));
            body.put("evidence_urls", summary == null ? List.of() : summary.get("evidence_urls"));
            body.put("release_sha", releaseInfo == null ? null : emptyToNull(releaseInfo.sha()));
            body.put("release_profile", releaseInfo == null ? null : emptyToNull(releaseInfo.profile()));
            body.put("data_source", dataSource);
            body.put("last_error", error);
            return body;
        }
    }
}
