package edu.illinois.group8.semantic;

import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.MarketMetadataReadRequest;
import edu.illinois.group8.storage.db.MarketSemanticMetadata;
import edu.illinois.group8.storage.db.MarketSemanticMetadataJob;
import edu.illinois.group8.storage.db.MarketSemanticMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMetadataBatchServiceTest {
    @Test
    void generatesMetadataAndPersistsJob() {
        FakeStore store = new FakeStore();
        FakeOpenRouter client = new FakeOpenRouter(List.of(new OpenRouterCompletion(
            "actual-model",
            "{\"sector\":\"politics\",\"subsector\":\"election\",\"event_type\":\"binary\",\"region\":\"US\",\"time_horizon\":\"near_term\",\"liquidity_bucket\":\"medium\",\"risk_bucket\":\"high\",\"tags\":[\"election\"],\"confidence\":0.8,\"rationale\":\"clear\"}",
            "{\"raw\":true}",
            "{\"total_tokens\":20}"
        )));

        SemanticMetadataBatchSummary summary = service(store, client)
            .run(config(Map.of("OPENROUTER_API_KEY", "sk-test")));

        assertEquals(1, summary.processed());
        assertEquals(1, summary.generated());
        assertEquals(0, summary.failed());
        assertEquals("actual-model", store.metadata.get(0).model());
        assertEquals("generated", store.metadata.get(0).status());
        assertEquals("[\"election\"]", store.metadata.get(0).tags());
        assertTrue(!store.metadata.get(0).sourcePayloadSha256().isBlank());
        assertTrue(!store.metadata.get(0).sourceFingerprint().isBlank());
        assertTrue(!store.metadata.get(0).idempotencyKey().isBlank());
        assertEquals("generated", store.jobs.get(store.jobs.size() - 1).status());
        assertTrue(store.jobs.get(store.jobs.size() - 1).usage().contains("\"provider_usage\""));
        assertTrue(store.jobs.get(store.jobs.size() - 1).usage().contains("\"estimated_spend_usd\""));
        assertEquals(List.of(SemanticMetadataConfig.DEFAULT_MAX_TOKENS), client.maxTokens);
    }

    @Test
    void retriesThenFallsBackOnPrimaryRateLimitAndRecordsRetryAfterWhenFallbackAlsoFails() {
        FakeStore fallbackStore = new FakeStore();
        FakeOpenRouter fallbackClient = new FakeOpenRouter(List.of(
            new OpenRouterException(429, "Bearer sk-secret primary"),
            new OpenRouterException(429, "Bearer sk-secret primary"),
            new OpenRouterCompletion(
                "paid-model",
                validContent("finance"),
                "{\"raw\":true}",
                null
            )
        ));

        SemanticMetadataBatchSummary fallbackSummary = service(fallbackStore, fallbackClient)
            .run(config(Map.of(
                "OPENROUTER_API_KEY", "sk-test",
                "LLM_METADATA_MAX_RETRIES", "1"
            )));

        assertEquals(1, fallbackSummary.generated());
        assertEquals(List.of(
                "deepseek/deepseek-v4-flash:free",
                "deepseek/deepseek-v4-flash:free",
                "deepseek/deepseek-v4-flash"
            ),
            fallbackClient.models);
        assertEquals(1, fallbackSummary.paidRequests());
        assertEquals(new BigDecimal("0.01"), fallbackSummary.estimatedSpendUsd());
        assertEquals("paid-model", fallbackStore.metadata.get(0).model());

        FakeStore limitedStore = new FakeStore();
        FakeOpenRouter limitedClient = new FakeOpenRouter(List.of(
            new OpenRouterException(429, "Bearer sk-secret primary"),
            new OpenRouterException(429, "Bearer sk-secret fallback", Duration.ofSeconds(7))
        ));
        SemanticMetadataBatchSummary limitedSummary = service(limitedStore, limitedClient)
            .run(config(Map.of(
                "OPENROUTER_API_KEY", "sk-test",
                "LLM_METADATA_MAX_RETRIES", "0"
            )));

        assertEquals(1, limitedSummary.rateLimited());
        assertEquals("rate_limited", limitedStore.metadata.get(0).status());
        assertEquals("rate_limited", limitedStore.jobs.get(limitedStore.jobs.size() - 1).status());
        assertTrue(limitedStore.jobs.get(limitedStore.jobs.size() - 1).nextRetryAt() != null);
        assertTrue(!limitedStore.metadata.get(0).error().contains("sk-secret"));
    }

    @Test
    void budgetCapPreventsPaidFallbackDispatch() {
        FakeStore store = new FakeStore();
        FakeOpenRouter client = new FakeOpenRouter(List.of(
            new OpenRouterException(429, "primary exhausted")
        ));

        SemanticMetadataBatchSummary summary = service(store, client).run(config(Map.of(
            "OPENROUTER_API_KEY", "sk-test",
            "LLM_METADATA_MAX_RETRIES", "0",
            "LLM_METADATA_BUDGET_USD", "0"
        )));

        assertEquals(1, summary.failed());
        assertEquals(List.of("deepseek/deepseek-v4-flash:free"), client.models);
        assertEquals("failed", store.metadata.get(0).status());
        assertTrue(store.metadata.get(0).error().contains("budget exceeded"));
        assertEquals(0, summary.paidRequests());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 402, 403})
    void terminalAuthOrBillingErrorDoesNotRetryOrFallbackToPaidModel(int statusCode) {
        FakeStore store = new FakeStore();
        FakeOpenRouter client = new FakeOpenRouter(List.of(
            new OpenRouterException(statusCode, "Bearer sk-secret-token-12345678901234567890 terminal")
        ));

        SemanticMetadataBatchSummary summary = service(store, client).run(config(Map.of(
            "OPENROUTER_API_KEY", "sk-test",
            "LLM_METADATA_MAX_RETRIES", "2"
        )));

        assertEquals(1, summary.failed());
        assertEquals(0, summary.rateLimited());
        assertEquals(0, summary.paidRequests());
        assertEquals(List.of("deepseek/deepseek-v4-flash:free"), client.models);
        assertEquals(1, store.metadata.size());
        assertEquals(2, store.jobs.size());

        MarketSemanticMetadata metadata = store.metadata.get(0);
        MarketSemanticMetadataJob job = store.jobs.get(store.jobs.size() - 1);
        assertEquals("failed", metadata.status());
        assertEquals("failed", job.status());
        assertEquals(1, job.attempts());
        assertEquals("deepseek/deepseek-v4-flash:free", job.actualModel());
        assertTrue(job.nextRetryAt() == null);
        assertTrue(!metadata.error().contains("sk-secret-token"));
        assertTrue(!job.error().contains("sk-secret-token"));
        assertTrue(!metadata.rawResponse().contains("sk-secret-token"));
        assertTrue(metadata.error().contains("[redacted]"));
        assertTrue(job.error().contains("[redacted]"));
        assertTrue(metadata.rawResponse().contains("[redacted]"));
    }

    @Test
    void malformedJsonBecomesReviewRequiredAndDryRunDoesNotCallClient() {
        FakeStore reviewStore = new FakeStore();
        SemanticMetadataBatchSummary reviewSummary = service(
            reviewStore,
            new FakeOpenRouter(List.of(new OpenRouterCompletion("m", "not-json", "{\"raw\":true}", null)))
        ).run(config(Map.of("OPENROUTER_API_KEY", "sk-test")));

        assertEquals(1, reviewSummary.reviewRequired());
        assertEquals("review_required", reviewStore.metadata.get(0).status());
        assertTrue(reviewStore.metadata.get(0).rawResponse().contains("\"raw\""));
        assertTrue(reviewStore.metadata.get(0).error().contains("max_tokens"));
        assertTrue(reviewStore.metadata.get(0).error().contains("truncated"));

        FakeOpenRouter dryRunClient = new FakeOpenRouter(List.of());
        SemanticMetadataBatchSummary dryRunSummary = service(new FakeStore(), dryRunClient)
            .run(config(Map.of("LLM_METADATA_DRY_RUN", "true")));

        assertEquals(1, dryRunSummary.processed());
        assertEquals(1, dryRunSummary.skipped());
        assertEquals(0, dryRunClient.models.size());
    }

    @Test
    void existingMetadataIsSkippedUnlessOverwrite() {
        FakeStore store = new FakeStore();
        store.existing = true;
        SemanticMetadataBatchSummary summary = service(store, new FakeOpenRouter(List.of()))
            .run(config(Map.of("OPENROUTER_API_KEY", "sk-test")));

        assertEquals(0, summary.processed());
        assertEquals(1, summary.skipped());
    }

    @Test
    void reviewRequiredMetadataIsRetriedWithSameInputIdentity() {
        FakeStore store = new FakeStore();
        store.existingMetadata = new MarketSemanticMetadata(
            "M",
            "v1",
            "m",
            "v1",
            new SemanticMetadataPromptBuilder().promptHash("v1"),
            new SemanticMetadataPromptBuilder().sourcePayloadSha256(market()),
            new SemanticMetadataPromptBuilder().sourceFingerprint(market(), config(Map.of("OPENROUTER_API_KEY", "sk-test"))),
            new SemanticMetadataPromptBuilder().idempotencyKey(market(), config(Map.of("OPENROUTER_API_KEY", "sk-test"))),
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
            "{\"raw\":true}",
            "review_required",
            "old parse failure",
            null
        );
        FakeOpenRouter client = new FakeOpenRouter(List.of(new OpenRouterCompletion(
            "m",
            validContent("sports"),
            "{\"raw\":true}",
            null
        )));

        SemanticMetadataBatchSummary summary = service(store, client)
            .run(config(Map.of("OPENROUTER_API_KEY", "sk-test")));

        assertEquals(1, summary.processed());
        assertEquals(1, summary.generated());
        assertEquals(1, client.models.size());
        assertEquals("generated", store.metadata.get(0).status());
    }

    @Test
    void defaultSelectionExcludesAlreadyGeneratedRowsByTaxonomy() {
        FakeStore store = new FakeStore();
        FakeOpenRouter client = new FakeOpenRouter(List.of(new OpenRouterCompletion(
            "m",
            validContent("sports"),
            "{\"raw\":true}",
            null
        )));
        List<MarketMetadataReadRequest> requests = new ArrayList<>();
        SemanticMetadataBatchService service =
            new SemanticMetadataBatchService(request -> {
                requests.add(request);
                return List.of(market());
            }, store, client);

        service.run(config(Map.of("OPENROUTER_API_KEY", "sk-test")));

        assertEquals(1, requests.size());
        assertEquals("v1", requests.get(0).excludeGeneratedTaxonomyVersion());
        assertTrue(requests.get(0).eligibleOnly());
    }

    @Test
    void changedSourcePayloadRegeneratesInsteadOfSkippingExistingMarket() {
        FakeStore store = new FakeStore();
        store.existingMetadata = new MarketSemanticMetadata(
            "M",
            "v1",
            "m",
            "v1",
            "prompt",
            "old-source-hash",
            "old-fingerprint",
            "old-idempotency",
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
            "{}",
            "generated",
            null,
            null
        );
        FakeOpenRouter client = new FakeOpenRouter(List.of(new OpenRouterCompletion(
            "m",
            validContent("politics"),
            "{\"raw\":true}",
            null
        )));

        SemanticMetadataBatchSummary summary = service(store, client)
            .run(config(Map.of("OPENROUTER_API_KEY", "sk-test")));

        assertEquals(1, summary.processed());
        assertEquals(1, summary.generated());
        assertEquals(1, client.models.size());
        assertTrue(!"old-idempotency".equals(store.metadata.get(0).idempotencyKey()));
    }

    private static SemanticMetadataBatchService service(FakeStore store, FakeOpenRouter client) {
        return new SemanticMetadataBatchService(request -> List.of(market()), store, client);
    }

    private static MarketMetadata market() {
        return new MarketMetadata(
            "M",
            "EVENT",
            "SERIES",
            "open",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-02-01T00:00:00Z"),
            null,
            "{\"rule\":\"yes\"}",
            "{\"ticker\":\"M\",\"title\":\"Will X win?\"}"
        );
    }

    private static String validContent(String sector) {
        return """
            {"sector":"%s","subsector":"election","event_type":"binary","region":"US","time_horizon":"near_term","liquidity_bucket":"medium","risk_bucket":"high","tags":["election"],"confidence":0.8,"rationale":"clear"}
            """.formatted(sector);
    }

    private static SemanticMetadataConfig config(Map<String, String> env) {
        java.util.HashMap<String, String> merged = new java.util.HashMap<>(env);
        merged.putIfAbsent("DB_WRITER_DATABASE_URL", "jdbc:postgresql://db/kalshi");
        return SemanticMetadataConfig.from(merged);
    }

    private static final class FakeStore implements MarketSemanticMetadataStore {
        private final List<MarketSemanticMetadata> metadata = new ArrayList<>();
        private final List<MarketSemanticMetadataJob> jobs = new ArrayList<>();
        private boolean existing;
        private MarketSemanticMetadata existingMetadata;

        @Override
        public void upsertMetadata(MarketSemanticMetadata metadata) {
            this.metadata.add(metadata);
        }

        @Override
        public void upsertJob(MarketSemanticMetadataJob job) {
            this.jobs.add(job);
        }

        @Override
        public Optional<MarketSemanticMetadata> findMetadata(String marketTicker, String taxonomyVersion) {
            if (existingMetadata != null) {
                return Optional.of(existingMetadata);
            }
            return existing ? Optional.of(metadata.isEmpty()
                ? new MarketSemanticMetadata("M", "v1", "m", "p", new SemanticMetadataPromptBuilder().promptHash("v1"),
                    new SemanticMetadataPromptBuilder().sourcePayloadSha256(market()),
                    new SemanticMetadataPromptBuilder().sourceFingerprint(market(), config(Map.of("OPENROUTER_API_KEY", "sk-test"))),
                    new SemanticMetadataPromptBuilder().idempotencyKey(market(), config(Map.of("OPENROUTER_API_KEY", "sk-test"))),
                    null, null, null, null, null, null, null, "[]", null, null,
                    "{}", "generated", null, null)
                : metadata.get(0)) : Optional.empty();
        }
    }

    private static final class FakeOpenRouter implements OpenRouterClient {
        private final List<Object> responses;
        private final List<String> models = new ArrayList<>();
        private final List<Integer> maxTokens = new ArrayList<>();
        private int index;

        private FakeOpenRouter(List<Object> responses) {
            this.responses = responses;
        }

        @Override
        public OpenRouterCompletion complete(String model, List<OpenRouterMessage> messages, int maxTokens) {
            models.add(model);
            this.maxTokens.add(maxTokens);
            Object response = responses.get(index++);
            if (response instanceof OpenRouterException exception) {
                throw exception;
            }
            return (OpenRouterCompletion) response;
        }
    }
}
