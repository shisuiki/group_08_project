package edu.illinois.group8.semantic;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public record SemanticMetadataConfig(
    String dbUrl,
    String dbUser,
    String dbPassword,
    String openRouterApiKey,
    String openRouterBaseUrl,
    String model,
    String fallbackModel,
    String taxonomyVersion,
    String promptVersion,
    int maxMarkets,
    int maxRetries,
    BigDecimal budgetUsd,
    BigDecimal estimatedPaidRequestCostUsd,
    int requestTimeoutMs,
    int retryBackoffMs,
    int maxTokens,
    String marketTicker,
    String seriesTicker,
    String marketStatus,
    boolean dryRun,
    boolean overwrite,
    String httpReferer,
    String appTitle
) {
    public static final String DEFAULT_MODEL = "deepseek/deepseek-v4-flash:free";
    public static final String DEFAULT_FALLBACK_MODEL = "deepseek/deepseek-v4-flash";
    public static final String DEFAULT_TAXONOMY_VERSION = "v1";
    public static final String DEFAULT_PROMPT_VERSION = "v1";

    public SemanticMetadataConfig {
        dbUrl = value(dbUrl);
        dbUser = value(dbUser);
        dbPassword = dbPassword == null ? "" : dbPassword;
        openRouterApiKey = openRouterApiKey == null ? "" : openRouterApiKey.trim();
        openRouterBaseUrl = value(openRouterBaseUrl);
        model = nonBlank(model, "LLM_METADATA_MODEL");
        fallbackModel = value(fallbackModel);
        taxonomyVersion = nonBlank(taxonomyVersion, "LLM_METADATA_TAXONOMY_VERSION");
        promptVersion = nonBlank(promptVersion, "LLM_METADATA_PROMPT_VERSION");
        marketTicker = value(marketTicker);
        seriesTicker = value(seriesTicker);
        marketStatus = value(marketStatus);
        httpReferer = value(httpReferer);
        appTitle = value(appTitle);
        if (maxMarkets < 1) {
            throw new IllegalArgumentException("LLM_METADATA_MAX_MARKETS must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("LLM_METADATA_MAX_RETRIES must be non-negative");
        }
        if (budgetUsd == null || budgetUsd.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("LLM_METADATA_BUDGET_USD must be non-negative");
        }
        if (estimatedPaidRequestCostUsd == null || estimatedPaidRequestCostUsd.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("LLM_METADATA_ESTIMATED_PAID_REQUEST_COST_USD must be positive");
        }
        if (requestTimeoutMs < 1) {
            throw new IllegalArgumentException("LLM_METADATA_REQUEST_TIMEOUT_MS must be positive");
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("LLM_METADATA_RETRY_BACKOFF_MS must be non-negative");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("LLM_METADATA_MAX_TOKENS must be positive");
        }
    }

    public static SemanticMetadataConfig fromEnvironment() {
        return from(System.getenv());
    }

    public static SemanticMetadataConfig from(Map<String, String> env) {
        String apiKey = value(env, "OPENROUTER_API_KEY", "");
        String apiKeyFile = value(env, "OPENROUTER_API_KEY_FILE", "");
        if (apiKey.isBlank() && !apiKeyFile.isBlank()) {
            apiKey = readSecret(Path.of(apiKeyFile));
        }
        return new SemanticMetadataConfig(
            value(env, "LLM_METADATA_DB_URL", value(env, "DB_WRITER_DATABASE_URL", "")),
            value(env, "LLM_METADATA_DB_USER", value(env, "DB_WRITER_DATABASE_USER", "")),
            value(env, "LLM_METADATA_DB_PASSWORD", value(env, "DB_WRITER_DATABASE_PASSWORD", "")),
            apiKey,
            value(env, "OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"),
            value(env, "LLM_METADATA_MODEL", DEFAULT_MODEL),
            value(env, "LLM_METADATA_FALLBACK_MODEL", DEFAULT_FALLBACK_MODEL),
            value(env, "LLM_METADATA_TAXONOMY_VERSION", DEFAULT_TAXONOMY_VERSION),
            value(env, "LLM_METADATA_PROMPT_VERSION", DEFAULT_PROMPT_VERSION),
            positiveInt(env, "LLM_METADATA_MAX_MARKETS", 10),
            nonNegativeInt(env, "LLM_METADATA_MAX_RETRIES", 2),
            decimal(env, "LLM_METADATA_BUDGET_USD", "10.00"),
            decimal(env, "LLM_METADATA_ESTIMATED_PAID_REQUEST_COST_USD", "0.01"),
            positiveInt(env, "LLM_METADATA_REQUEST_TIMEOUT_MS", 30_000),
            nonNegativeInt(env, "LLM_METADATA_RETRY_BACKOFF_MS", 2_000),
            positiveInt(env, "LLM_METADATA_MAX_TOKENS", 900),
            value(env, "LLM_METADATA_MARKET_TICKER", ""),
            value(env, "LLM_METADATA_SERIES_TICKER", ""),
            value(env, "LLM_METADATA_MARKET_STATUS", "open"),
            booleanValue(env, "LLM_METADATA_DRY_RUN", false),
            booleanValue(env, "LLM_METADATA_OVERWRITE", false),
            value(env, "OPENROUTER_HTTP_REFERER", ""),
            value(env, "OPENROUTER_APP_TITLE", "kalshi-semantic-metadata")
        );
    }

    public SemanticMetadataConfig withArgs(String[] args) {
        SemanticMetadataConfig next = this;
        if (args == null) {
            return next;
        }
        for (String arg : args) {
            if ("--dry-run".equals(arg)) {
                next = next.withDryRun(true);
            } else if ("--overwrite".equals(arg)) {
                next = next.withOverwrite(true);
            } else if (arg.startsWith("--limit=")) {
                next = next.withMaxMarkets(Integer.parseInt(arg.substring("--limit=".length())));
            } else if (arg.startsWith("--market=")) {
                next = next.withMarketTicker(arg.substring("--market=".length()));
            } else if (arg.startsWith("--series=")) {
                next = next.withSeriesTicker(arg.substring("--series=".length()));
            } else if (arg.startsWith("--status=")) {
                next = next.withMarketStatus(arg.substring("--status=".length()));
            } else if (arg.startsWith("--model=")) {
                next = next.withModel(arg.substring("--model=".length()));
            } else if (arg.startsWith("--fallback-model=")) {
                next = next.withFallbackModel(arg.substring("--fallback-model=".length()));
            } else if (arg.startsWith("--max-retries=")) {
                next = next.withMaxRetries(Integer.parseInt(arg.substring("--max-retries=".length())));
            } else if (arg.startsWith("--max-tokens=")) {
                next = next.withMaxTokens(Integer.parseInt(arg.substring("--max-tokens=".length())));
            } else if (arg.startsWith("--budget-usd=")) {
                next = next.withBudgetUsd(new BigDecimal(arg.substring("--budget-usd=".length())));
            } else if (arg.startsWith("--estimated-paid-request-cost-usd=")) {
                next = next.withEstimatedPaidRequestCostUsd(
                    new BigDecimal(arg.substring("--estimated-paid-request-cost-usd=".length()))
                );
            } else if (arg.startsWith("--taxonomy-version=")) {
                next = next.withTaxonomyVersion(arg.substring("--taxonomy-version=".length()));
            } else if (arg.startsWith("--prompt-version=")) {
                next = next.withPromptVersion(arg.substring("--prompt-version=".length()));
            } else if (!"--help".equals(arg) && !"-h".equals(arg)) {
                throw new IllegalArgumentException("Unknown semantic metadata argument: " + arg);
            }
        }
        return next;
    }

    public SemanticMetadataConfig validateForRun() {
        if (dbUrl.isBlank()) {
            throw new IllegalArgumentException("LLM_METADATA_DB_URL or DB_WRITER_DATABASE_URL is required");
        }
        if (!dryRun && openRouterApiKey.isBlank()) {
            throw new IllegalArgumentException("OPENROUTER_API_KEY or OPENROUTER_API_KEY_FILE is required");
        }
        return this;
    }

    public Duration requestTimeout() {
        return Duration.ofMillis(requestTimeoutMs);
    }

    String redactedSummary() {
        return "model=" + model
            + " fallback_model=" + fallbackModel
            + " taxonomy_version=" + taxonomyVersion
            + " prompt_version=" + promptVersion
            + " max_markets=" + maxMarkets
            + " max_retries=" + maxRetries
            + " budget_usd=" + budgetUsd
            + " estimated_paid_request_cost_usd=" + estimatedPaidRequestCostUsd
            + " dry_run=" + dryRun
            + " openrouter_key=" + SemanticMetadataRedactor.configuredValue(!openRouterApiKey.isBlank());
    }

    private SemanticMetadataConfig withDryRun(boolean value) {
        return copy(maxMarkets, marketTicker, seriesTicker, marketStatus, model, fallbackModel,
            taxonomyVersion, promptVersion, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, value, overwrite);
    }

    private SemanticMetadataConfig withOverwrite(boolean value) {
        return copy(maxMarkets, marketTicker, seriesTicker, marketStatus, model, fallbackModel,
            taxonomyVersion, promptVersion, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, dryRun, value);
    }

    private SemanticMetadataConfig withMaxMarkets(int value) {
        return copy(value, marketTicker, seriesTicker, marketStatus, model, fallbackModel,
            taxonomyVersion, promptVersion, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig withMarketTicker(String value) {
        return copy(maxMarkets, value, seriesTicker, marketStatus, model, fallbackModel,
            taxonomyVersion, promptVersion, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig withSeriesTicker(String value) {
        return copy(maxMarkets, marketTicker, value, marketStatus, model, fallbackModel,
            taxonomyVersion, promptVersion, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig withMarketStatus(String value) {
        return copy(maxMarkets, marketTicker, seriesTicker, value, model, fallbackModel,
            taxonomyVersion, promptVersion, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig withModel(String value) {
        return copy(maxMarkets, marketTicker, seriesTicker, marketStatus, value, fallbackModel,
            taxonomyVersion, promptVersion, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig withFallbackModel(String value) {
        return copy(maxMarkets, marketTicker, seriesTicker, marketStatus, model, value,
            taxonomyVersion, promptVersion, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig withMaxRetries(int value) {
        return copy(maxMarkets, marketTicker, seriesTicker, marketStatus, model, fallbackModel,
            taxonomyVersion, promptVersion, value, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig withBudgetUsd(BigDecimal value) {
        return copy(maxMarkets, marketTicker, seriesTicker, marketStatus, model, fallbackModel,
            taxonomyVersion, promptVersion, maxRetries, value, estimatedPaidRequestCostUsd, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig withEstimatedPaidRequestCostUsd(BigDecimal value) {
        return copy(maxMarkets, marketTicker, seriesTicker, marketStatus, model, fallbackModel,
            taxonomyVersion, promptVersion, maxRetries, budgetUsd, value, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig withMaxTokens(int value) {
        return copy(maxMarkets, marketTicker, seriesTicker, marketStatus, model, fallbackModel,
            taxonomyVersion, promptVersion, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, value, dryRun, overwrite);
    }

    private SemanticMetadataConfig withTaxonomyVersion(String value) {
        return copy(maxMarkets, marketTicker, seriesTicker, marketStatus, model, fallbackModel,
            value, promptVersion, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig withPromptVersion(String value) {
        return copy(maxMarkets, marketTicker, seriesTicker, marketStatus, model, fallbackModel,
            taxonomyVersion, value, maxRetries, budgetUsd, estimatedPaidRequestCostUsd, maxTokens, dryRun, overwrite);
    }

    private SemanticMetadataConfig copy(
        int nextMaxMarkets,
        String nextMarketTicker,
        String nextSeriesTicker,
        String nextMarketStatus,
        String nextModel,
        String nextFallbackModel,
        String nextTaxonomyVersion,
        String nextPromptVersion,
        int nextMaxRetries,
        BigDecimal nextBudgetUsd,
        BigDecimal nextEstimatedPaidRequestCostUsd,
        int nextMaxTokens,
        boolean nextDryRun,
        boolean nextOverwrite
    ) {
        return new SemanticMetadataConfig(
            dbUrl,
            dbUser,
            dbPassword,
            openRouterApiKey,
            openRouterBaseUrl,
            nextModel,
            nextFallbackModel,
            nextTaxonomyVersion,
            nextPromptVersion,
            nextMaxMarkets,
            nextMaxRetries,
            nextBudgetUsd,
            nextEstimatedPaidRequestCostUsd,
            requestTimeoutMs,
            retryBackoffMs,
            nextMaxTokens,
            nextMarketTicker,
            nextSeriesTicker,
            nextMarketStatus,
            nextDryRun,
            nextOverwrite,
            httpReferer,
            appTitle
        );
    }

    private static String readSecret(Path path) {
        try {
            return Files.readString(path).trim();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read OPENROUTER_API_KEY_FILE", e);
        }
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value.trim();
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static String value(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int positiveInt(Map<String, String> env, String key, int defaultValue) {
        int value = Integer.parseInt(value(env, key, Integer.toString(defaultValue)));
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be positive: " + value);
        }
        return value;
    }

    private static int nonNegativeInt(Map<String, String> env, String key, int defaultValue) {
        int value = Integer.parseInt(value(env, key, Integer.toString(defaultValue)));
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be non-negative: " + value);
        }
        return value;
    }

    private static BigDecimal decimal(Map<String, String> env, String key, String defaultValue) {
        return new BigDecimal(value(env, key, defaultValue));
    }

    private static boolean booleanValue(Map<String, String> env, String key, boolean defaultValue) {
        return Boolean.parseBoolean(value(env, key, Boolean.toString(defaultValue)));
    }
}
