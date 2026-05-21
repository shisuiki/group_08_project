package edu.illinois.group8.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.MarketMetadataReadRequest;
import edu.illinois.group8.storage.db.MarketMetadataReader;
import edu.illinois.group8.storage.db.MarketSemanticMetadata;
import edu.illinois.group8.storage.db.MarketSemanticMetadataJob;
import edu.illinois.group8.storage.db.MarketSemanticMetadataStore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SemanticMetadataBatchService {
    private final MarketMetadataReader marketReader;
    private final MarketSemanticMetadataStore store;
    private final OpenRouterClient openRouterClient;
    private final SemanticMetadataPromptBuilder promptBuilder;
    private final ObjectMapper mapper;

    public SemanticMetadataBatchService(
        MarketMetadataReader marketReader,
        MarketSemanticMetadataStore store,
        OpenRouterClient openRouterClient
    ) {
        this(
            marketReader,
            store,
            openRouterClient,
            new SemanticMetadataPromptBuilder(),
            new JsonCanonicalSerializer().mapper().copy()
        );
    }

    SemanticMetadataBatchService(
        MarketMetadataReader marketReader,
        MarketSemanticMetadataStore store,
        OpenRouterClient openRouterClient,
        SemanticMetadataPromptBuilder promptBuilder,
        ObjectMapper mapper
    ) {
        this.marketReader = Objects.requireNonNull(marketReader, "marketReader");
        this.store = Objects.requireNonNull(store, "store");
        this.openRouterClient = Objects.requireNonNull(openRouterClient, "openRouterClient");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public SemanticMetadataBatchSummary run(SemanticMetadataConfig config) {
        Objects.requireNonNull(config, "config");
        List<MarketMetadata> markets = marketReader.read(readRequest(config));
        int processed = 0;
        int generated = 0;
        int reviewRequired = 0;
        int rateLimited = 0;
        int failed = 0;
        int skipped = 0;
        BudgetTracker budgetTracker = new BudgetTracker(
            config.budgetUsd(),
            config.estimatedPaidRequestCostUsd()
        );

        for (MarketMetadata market : markets) {
            if (shouldSkipExisting(market, config)) {
                skipped++;
                continue;
            }
            processed++;
            if (config.dryRun()) {
                skipped++;
                continue;
            }
            Result result = classifyAndPersist(market, config, budgetTracker);
            switch (result.status()) {
                case "generated" -> generated++;
                case "review_required" -> reviewRequired++;
                case "rate_limited" -> rateLimited++;
                default -> failed++;
            }
        }

        return new SemanticMetadataBatchSummary(
            processed,
            generated,
            reviewRequired,
            rateLimited,
            failed,
            skipped,
            budgetTracker.paidRequests(),
            budgetTracker.estimatedSpendUsd(),
            config.model(),
            config.fallbackModel()
        );
    }

    private boolean shouldSkipExisting(MarketMetadata market, SemanticMetadataConfig config) {
        if (config.overwrite() || config.dryRun()) {
            return false;
        }
        InputIdentity identity = inputIdentity(market, config);
        return store.findMetadata(market.marketTicker(), config.taxonomyVersion())
            .filter(metadata -> identity.idempotencyKey().equals(metadata.idempotencyKey()))
            .filter(metadata -> reusableTerminalStatus(metadata.status()))
            .isPresent();
    }

    private static boolean reusableTerminalStatus(String status) {
        return "generated".equals(status);
    }

    private Result classifyAndPersist(
        MarketMetadata market,
        SemanticMetadataConfig config,
        BudgetTracker budgetTracker
    ) {
        InputIdentity identity = inputIdentity(market, config);

        upsertJob(identity, market, config, config.model(), null, "running", 0, null, null, null);
        ModelAttempt attempt = completeWithRetries(market, config, budgetTracker);
        OpenRouterCompletion completion = attempt.completion();
        String actualModel = attempt.actualModel();

        if (completion == null) {
            OpenRouterException openRouterFailure = attempt.failure();
            String status = openRouterFailure != null && openRouterFailure.rateLimited() ? "rate_limited" : "failed";
            String error = attempt.error() != null
                ? attempt.error()
                : openRouterFailure == null ? "OpenRouter completion failed" : openRouterFailure.getMessage();
            Instant retryAt = retryAt(openRouterFailure, config);
            persistStatusOnly(identity, market, config, actualModel, status, error);
            upsertJob(
                identity,
                market,
                config,
                config.model(),
                actualModel,
                status,
                attempt.attempts(),
                retryAt,
                error,
                usagePayload(null, budgetTracker)
            );
            return new Result(status);
        }

        actualModel = completion.model().isBlank() ? actualModel : completion.model();
        try {
            SemanticMetadataClassification classification =
                SemanticMetadataClassification.parse(completion.content(), mapper);
            MarketSemanticMetadata metadata = new MarketSemanticMetadata(
                market.marketTicker(),
                config.taxonomyVersion(),
                actualModel,
                config.promptVersion(),
                identity.promptHash(),
                identity.sourcePayloadSha256(),
                identity.sourceFingerprint(),
                identity.idempotencyKey(),
                classification.sector(),
                classification.subsector(),
                classification.eventType(),
                classification.region(),
                classification.timeHorizon(),
                classification.liquidityBucket(),
                classification.riskBucket(),
                classification.tags(),
                classification.confidence(),
                classification.rationale(),
                completion.rawResponse(),
                "generated",
                null,
                Instant.now()
            );
            store.upsertMetadata(metadata);
            upsertJob(identity, market, config, config.model(), actualModel, "generated",
                attempt.attempts(), null, null, usagePayload(completion.usage(), budgetTracker));
            return new Result("generated");
        } catch (IllegalArgumentException e) {
            String error = parseFailureError(e, completion, config);
            persistStatusOnly(identity, market, config, actualModel, "review_required", error, completion.rawResponse());
            upsertJob(identity, market, config, config.model(), actualModel, "review_required",
                attempt.attempts(), null, error, usagePayload(completion.usage(), budgetTracker));
            return new Result("review_required");
        }
    }

    private ModelAttempt completeWithRetries(
        MarketMetadata market,
        SemanticMetadataConfig config,
        BudgetTracker budgetTracker
    ) {
        List<OpenRouterMessage> messages = promptBuilder.messages(market, config);
        ModelAttempt primary = tryModel(config.model(), config, budgetTracker, messages, 0);
        if (primary.completion() != null
            || primary.failure() == null
            || primary.failure().terminalConfigOrBilling()
            || !primary.failure().unavailableForFallback()
            || !fallbackConfigured(config)) {
            return primary;
        }
        return tryModel(config.fallbackModel(), config, budgetTracker, messages, primary.attempts());
    }

    private ModelAttempt tryModel(
        String model,
        SemanticMetadataConfig config,
        BudgetTracker budgetTracker,
        List<OpenRouterMessage> messages,
        int previousAttempts
    ) {
        int attempts = previousAttempts;
        OpenRouterException lastFailure = null;
        int maxAttempts = config.maxRetries() + 1;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (!budgetTracker.reserveIfPaid(model)) {
                return new ModelAttempt(
                    null,
                    model,
                    attempts,
                    null,
                    "paid OpenRouter request budget exceeded before dispatch"
                );
            }
            attempts++;
            try {
                return new ModelAttempt(
                    openRouterClient.complete(model, messages, config.maxTokens()),
                    model,
                    attempts,
                    null,
                    null
                );
            } catch (OpenRouterException e) {
                lastFailure = e;
                if (e.terminalConfigOrBilling() || !e.retryable() || attempt + 1 >= maxAttempts) {
                    break;
                }
            } catch (RuntimeException e) {
                return new ModelAttempt(
                    null,
                    model,
                    attempts,
                    null,
                    SemanticMetadataRedactor.redact(e.getMessage())
                );
            }
        }
        return new ModelAttempt(null, model, attempts, lastFailure, null);
    }

    private static boolean fallbackConfigured(SemanticMetadataConfig config) {
        return !config.fallbackModel().isBlank() && !config.fallbackModel().equals(config.model());
    }

    private static Instant retryAt(OpenRouterException failure, SemanticMetadataConfig config) {
        if (failure == null || !failure.retryable()) {
            return null;
        }
        long retryMillis = failure.retryAfter() == null
            ? config.retryBackoffMs()
            : failure.retryAfter().toMillis();
        return Instant.now().plusMillis(Math.max(0, retryMillis));
    }

    private void persistStatusOnly(
        InputIdentity identity,
        MarketMetadata market,
        SemanticMetadataConfig config,
        String actualModel,
        String status,
        String error
    ) {
        persistStatusOnly(identity, market, config, actualModel, status, error, errorPayload(error));
    }

    private void persistStatusOnly(
        InputIdentity identity,
        MarketMetadata market,
        SemanticMetadataConfig config,
        String actualModel,
        String status,
        String error,
        String rawResponse
    ) {
        store.upsertMetadata(new MarketSemanticMetadata(
            market.marketTicker(),
            config.taxonomyVersion(),
            actualModel == null || actualModel.isBlank() ? config.model() : actualModel,
            config.promptVersion(),
            identity.promptHash(),
            identity.sourcePayloadSha256(),
            identity.sourceFingerprint(),
            identity.idempotencyKey(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "[]",
            null,
            null,
            rawResponse == null || rawResponse.isBlank() ? "{}" : rawResponse,
            status,
            error,
            "generated".equals(status) ? Instant.now() : null
        ));
    }

    private void upsertJob(
        InputIdentity identity,
        MarketMetadata market,
        SemanticMetadataConfig config,
        String requestedModel,
        String actualModel,
        String status,
        int attempts,
        Instant nextRetryAt,
        String error,
        String usage
    ) {
        store.upsertJob(new MarketSemanticMetadataJob(
            identity.jobId(),
            market.marketTicker(),
            config.taxonomyVersion(),
            identity.promptHash(),
            identity.sourcePayloadSha256(),
            identity.sourceFingerprint(),
            identity.idempotencyKey(),
            requestedModel,
            actualModel,
            status,
            attempts,
            nextRetryAt,
            error,
            usage
        ));
    }

    private InputIdentity inputIdentity(MarketMetadata market, SemanticMetadataConfig config) {
        return new InputIdentity(
            promptBuilder.promptHash(config.promptVersion()),
            promptBuilder.sourcePayloadSha256(market),
            promptBuilder.sourceFingerprint(market, config),
            promptBuilder.idempotencyKey(market, config),
            promptBuilder.jobId(market, config)
        );
    }

    private static MarketMetadataReadRequest readRequest(SemanticMetadataConfig config) {
        if (!config.marketTicker().isBlank()) {
            return MarketMetadataReadRequest.byTicker(config.marketTicker());
        }
        if (!config.overwrite() && !config.dryRun()) {
            return MarketMetadataReadRequest.searchWithoutGenerated(
                config.seriesTicker().isBlank() ? null : config.seriesTicker(),
                config.marketStatus().isBlank() ? null : config.marketStatus(),
                config.maxMarkets(),
                config.taxonomyVersion()
            ).withEligibleOnly();
        }
        return MarketMetadataReadRequest.search(
            config.seriesTicker().isBlank() ? null : config.seriesTicker(),
            config.marketStatus().isBlank() ? null : config.marketStatus(),
            config.maxMarkets()
        ).withEligibleOnly();
    }

    private String parseFailureError(
        IllegalArgumentException failure,
        OpenRouterCompletion completion,
        SemanticMetadataConfig config
    ) {
        String base = failure.getMessage() == null || failure.getMessage().isBlank()
            ? "Failed to parse semantic metadata JSON"
            : failure.getMessage();
        if (!looksTruncated(completion)) {
            return base;
        }
        return base
            + "; response appears truncated or ended by provider length limit at max_tokens="
            + config.maxTokens()
            + ". Retry with higher max_tokens; review_required rows are retried by default unless generated metadata exists.";
    }

    private boolean looksTruncated(OpenRouterCompletion completion) {
        String content = completion.content() == null ? "" : completion.content().trim();
        if (content.isBlank() || !content.endsWith("}")) {
            return true;
        }
        try {
            JsonNode root = mapper.readTree(completion.rawResponse());
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String finishReason = choices.get(0).path("finish_reason").asText("");
                return "length".equalsIgnoreCase(finishReason);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String errorPayload(String error) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("error", SemanticMetadataRedactor.redact(error));
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"redacted\"}";
        }
    }

    private String usagePayload(String providerUsage, BudgetTracker budgetTracker) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (providerUsage != null && !providerUsage.isBlank()) {
                JsonNode usage = mapper.readTree(providerUsage);
                payload.put("provider_usage", usage);
            }
            payload.put("paid_requests", budgetTracker.paidRequests());
            payload.put("estimated_spend_usd", budgetTracker.estimatedSpendUsd().toPlainString());
            payload.put("budget_usd", budgetTracker.budgetUsd().toPlainString());
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"paid_requests\":" + budgetTracker.paidRequests()
                + ",\"estimated_spend_usd\":\"" + budgetTracker.estimatedSpendUsd().toPlainString()
                + "\",\"budget_usd\":\"" + budgetTracker.budgetUsd().toPlainString() + "\"}";
        }
    }

    private record InputIdentity(
        String promptHash,
        String sourcePayloadSha256,
        String sourceFingerprint,
        String idempotencyKey,
        String jobId
    ) {
    }

    private record ModelAttempt(
        OpenRouterCompletion completion,
        String actualModel,
        int attempts,
        OpenRouterException failure,
        String error
    ) {
    }

    private record Result(String status) {
    }

    private static final class BudgetTracker {
        private final BigDecimal budgetUsd;
        private final BigDecimal estimatedPaidRequestCostUsd;
        private int paidRequests;
        private BigDecimal estimatedSpendUsd = BigDecimal.ZERO;

        private BudgetTracker(BigDecimal budgetUsd, BigDecimal estimatedPaidRequestCostUsd) {
            this.budgetUsd = Objects.requireNonNull(budgetUsd, "budgetUsd");
            this.estimatedPaidRequestCostUsd =
                Objects.requireNonNull(estimatedPaidRequestCostUsd, "estimatedPaidRequestCostUsd");
        }

        private boolean reserveIfPaid(String model) {
            if (model == null || model.contains(":free")) {
                return true;
            }
            BigDecimal nextSpend = estimatedSpendUsd.add(estimatedPaidRequestCostUsd);
            if (nextSpend.compareTo(budgetUsd) > 0) {
                return false;
            }
            paidRequests++;
            estimatedSpendUsd = nextSpend;
            return true;
        }

        private int paidRequests() {
            return paidRequests;
        }

        private BigDecimal estimatedSpendUsd() {
            return estimatedSpendUsd;
        }

        private BigDecimal budgetUsd() {
            return budgetUsd;
        }
    }
}
