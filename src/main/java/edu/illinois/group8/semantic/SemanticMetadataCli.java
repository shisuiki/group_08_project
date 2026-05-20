package edu.illinois.group8.semantic;

import edu.illinois.group8.storage.db.JdbcMarketMetadataReader;
import edu.illinois.group8.storage.db.JdbcMarketSemanticMetadataStore;
import edu.illinois.group8.storage.db.MarketMetadataReader;
import edu.illinois.group8.storage.db.MarketSemanticMetadataStore;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Map;

public final class SemanticMetadataCli {
    private SemanticMetadataCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (Arrays.stream(args == null ? new String[0] : args).anyMatch(arg -> "--help".equals(arg) || "-h".equals(arg))) {
            printUsage(out);
            return 0;
        }
        try {
            SemanticMetadataConfig config = SemanticMetadataConfig.from(env).withArgs(args).validateForRun();
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
            SemanticMetadataBatchSummary summary =
                new SemanticMetadataBatchService(marketReader, store, client).run(config);
            out.println("semantic_metadata_config " + config.redactedSummary());
            out.println("semantic_metadata_summary " + summary.toSummaryLine());
            return summary.failed() > 0 || summary.rateLimited() > 0 || summary.reviewRequired() > 0 ? 2 : 0;
        } catch (Exception e) {
            err.println("semantic_metadata_error=" + SemanticMetadataRedactor.redact(e.getMessage()));
            return 1;
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: SemanticMetadataCli [--dry-run] [--limit=N] [--market=TICKER] [--series=SERIES]");
        out.println("Options: [--max-retries=N] [--max-tokens=N] [--budget-usd=USD] "
            + "[--estimated-paid-request-cost-usd=USD]");
        out.println("Environment: OPENROUTER_API_KEY or OPENROUTER_API_KEY_FILE, "
            + "LLM_METADATA_DB_URL/DB_WRITER_DATABASE_URL, LLM_METADATA_MODEL, "
            + "LLM_METADATA_FALLBACK_MODEL, LLM_METADATA_TAXONOMY_VERSION");
    }
}
