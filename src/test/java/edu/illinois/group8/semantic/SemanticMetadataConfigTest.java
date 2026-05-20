package edu.illinois.group8.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMetadataConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesDefaultsAndDbFallbacks() {
        SemanticMetadataConfig config = SemanticMetadataConfig.from(Map.of(
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://db/kalshi",
            "DB_WRITER_DATABASE_USER", "kalshi",
            "DB_WRITER_DATABASE_PASSWORD", "secret",
            "OPENROUTER_API_KEY", "sk-test-key"
        ));

        assertEquals("jdbc:postgresql://db/kalshi", config.dbUrl());
        assertEquals("kalshi", config.dbUser());
        assertEquals("secret", config.dbPassword());
        assertEquals(SemanticMetadataConfig.DEFAULT_MODEL, config.model());
        assertEquals(SemanticMetadataConfig.DEFAULT_FALLBACK_MODEL, config.fallbackModel());
        assertEquals("v1", config.taxonomyVersion());
        assertEquals(10, config.maxMarkets());
        assertEquals(2, config.maxRetries());
        assertEquals(SemanticMetadataConfig.DEFAULT_MAX_TOKENS, config.maxTokens());
        assertEquals("10.00", config.budgetUsd().toPlainString());
        assertEquals("0.01", config.estimatedPaidRequestCostUsd().toPlainString());
        assertEquals("sk-test-key", config.openRouterApiKey());
        assertTrue(config.redactedSummary().contains("openrouter_key=[configured]"));
        assertTrue(config.redactedSummary().contains("budget_usd=10.00"));
        assertTrue(config.redactedSummary().contains("max_tokens=" + SemanticMetadataConfig.DEFAULT_MAX_TOKENS));
        assertTrue(!config.redactedSummary().contains("sk-test-key"));
    }

    @Test
    void readsApiKeyFileAndArgsOverrideSafely() throws Exception {
        Path keyFile = tempDir.resolve("openrouter.key");
        Files.writeString(keyFile, "sk-file-key\n");

        SemanticMetadataConfig config = SemanticMetadataConfig.from(Map.of(
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://db/kalshi",
            "OPENROUTER_API_KEY_FILE", keyFile.toString(),
            "LLM_METADATA_DRY_RUN", "true"
        )).withArgs(new String[] {
            "--limit=1",
            "--market=M",
            "--model=primary",
            "--fallback-model=fallback",
            "--max-retries=4",
            "--max-tokens=200",
            "--budget-usd=1.50",
            "--estimated-paid-request-cost-usd=0.05",
            "--taxonomy-version=tax2",
            "--prompt-version=prompt2"
        });

        assertEquals("sk-file-key", config.openRouterApiKey());
        assertEquals(1, config.maxMarkets());
        assertEquals("M", config.marketTicker());
        assertEquals("primary", config.model());
        assertEquals("fallback", config.fallbackModel());
        assertEquals(4, config.maxRetries());
        assertEquals(200, config.maxTokens());
        assertEquals("1.50", config.budgetUsd().toPlainString());
        assertEquals("0.05", config.estimatedPaidRequestCostUsd().toPlainString());
        assertEquals("tax2", config.taxonomyVersion());
        assertEquals("prompt2", config.promptVersion());
    }

    @Test
    void validationAllowsDryRunWithoutKeyButRejectsMissingRuntimeInputs() {
        assertThrows(
            IllegalArgumentException.class,
            () -> SemanticMetadataConfig.from(Map.of()).validateForRun()
        );

        SemanticMetadataConfig dryRun = SemanticMetadataConfig.from(Map.of(
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://db/kalshi",
            "LLM_METADATA_DRY_RUN", "true"
        ));
        dryRun.validateForRun();

        SemanticMetadataConfig noKey = SemanticMetadataConfig.from(Map.of(
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://db/kalshi"
        ));
        assertThrows(IllegalArgumentException.class, noKey::validateForRun);
        assertThrows(
            IllegalArgumentException.class,
            () -> SemanticMetadataConfig.from(Map.of("LLM_METADATA_MAX_MARKETS", "0"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SemanticMetadataConfig.from(Map.of("LLM_METADATA_REQUEST_TIMEOUT_MS", "0"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SemanticMetadataConfig.from(Map.of("LLM_METADATA_ESTIMATED_PAID_REQUEST_COST_USD", "0"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SemanticMetadataConfig.from(Map.of("LLM_METADATA_MAX_TOKENS", "4097"))
        );
    }
}
