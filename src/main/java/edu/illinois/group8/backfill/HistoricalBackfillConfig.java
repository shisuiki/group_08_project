package edu.illinois.group8.backfill;

import edu.illinois.group8.recorder.StreamRecordingWriter;
import edu.illinois.group8.time.TimestampSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record HistoricalBackfillConfig(
    String kalshiBaseUrl,
    String kalshiKeyId,
    String kalshiKeyPath,
    Path outputRoot,
    String canonicalSubtree,
    CanonicalTarget canonicalTarget,
    String dbUrl,
    String dbUser,
    String dbPassword,
    boolean rawRestEnabled,
    Path rawRestOutputRoot,
    List<String> tickers,
    String seriesTicker,
    String marketStatus,
    String marketMveFilter,
    int limit,
    int maxPages,
    int maxTickers,
    Integer startTs,
    Integer endTs,
    Integer periodInterval,
    boolean includeMarkets,
    boolean includeTrades,
    boolean includeOrderbookSnapshots,
    boolean includeCandlesticks,
    boolean dryRun,
    TimestampSource timestampSource,
    StreamRecordingWriter.PartitionGranularity partitionGranularity
) {
    public enum CanonicalTarget {
        DB, RECORDING;

        public static CanonicalTarget from(String value) {
            if (value == null || value.isBlank()) {
                return DB;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "db", "postgres", "postgresql", "timescale", "timescaledb" -> DB;
                case "recording", "history", "storage", "ndjson" -> RECORDING;
                default -> throw new IllegalArgumentException("Unknown HISTORICAL_BACKFILL_CANONICAL_TARGET: " + value);
            };
        }
    }

    public static HistoricalBackfillConfig fromEnvironment() {
        return from(System.getenv());
    }

    static HistoricalBackfillConfig from(Map<String, String> env) {
        String baseDir = value(env, "BASE_DIR", "/app");
        return new HistoricalBackfillConfig(
            value(env, "KALSHI_BASE_URL", "https://api.elections.kalshi.com"),
            value(env, "KALSHI_KEY_ID", ""),
            value(env, "KALSHI_KEY_PATH", ""),
            Path.of(value(env, "HISTORICAL_BACKFILL_OUTPUT_ROOT", baseDir + "/recordings")),
            value(env, "HISTORICAL_BACKFILL_CANONICAL_SUBTREE", "canonical"),
            CanonicalTarget.from(value(env, "HISTORICAL_BACKFILL_CANONICAL_TARGET", "db")),
            value(env, "HISTORICAL_BACKFILL_DB_URL", value(env, "DB_WRITER_DATABASE_URL", "")),
            value(env, "HISTORICAL_BACKFILL_DB_USER", value(env, "DB_WRITER_DATABASE_USER", "")),
            value(env, "HISTORICAL_BACKFILL_DB_PASSWORD", value(env, "DB_WRITER_DATABASE_PASSWORD", "")),
            booleanValue(env, "HISTORICAL_BACKFILL_RAW_REST_ENABLED", false),
            Path.of(value(env, "HISTORICAL_BACKFILL_RAW_REST_ROOT", baseDir + "/recordings/raw-rest")),
            csv(value(env, "HISTORICAL_BACKFILL_TICKERS", value(env, "KALSHI_MARKET_TICKERS", ""))),
            value(env, "HISTORICAL_BACKFILL_SERIES_TICKER", value(env, "KALSHI_MARKET_SERIES_TICKER", "")),
            value(env, "HISTORICAL_BACKFILL_MARKET_STATUS", value(env, "KALSHI_MARKET_STATUS", "open")),
            value(env, "HISTORICAL_BACKFILL_MVE_FILTER", value(env, "KALSHI_MARKET_MVE_FILTER", "")),
            positiveIntValue(env, "HISTORICAL_BACKFILL_LIMIT", 1000),
            positiveIntValue(env, "HISTORICAL_BACKFILL_MAX_PAGES", 1),
            nonNegativeIntValue(env, "HISTORICAL_BACKFILL_MAX_TICKERS", 0),
            optionalInt(env, "HISTORICAL_BACKFILL_START_TS"),
            optionalInt(env, "HISTORICAL_BACKFILL_END_TS"),
            optionalInt(env, "HISTORICAL_BACKFILL_PERIOD_INTERVAL"),
            booleanValue(env, "HISTORICAL_BACKFILL_INCLUDE_MARKETS", true),
            booleanValue(env, "HISTORICAL_BACKFILL_INCLUDE_TRADES", true),
            booleanValue(env, "HISTORICAL_BACKFILL_INCLUDE_ORDERBOOK_SNAPSHOTS", false),
            booleanValue(env, "HISTORICAL_BACKFILL_INCLUDE_CANDLESTICKS", false),
            booleanValue(env, "HISTORICAL_BACKFILL_DRY_RUN", false),
            TimestampSource.fromEnvironment(),
            StreamRecordingWriter.PartitionGranularity.from(value(env, "HISTORICAL_BACKFILL_PARTITION_GRANULARITY", "hour"))
        );
    }

    public HistoricalBackfillConfig validate() {
        if (canonicalTarget == null) {
            throw new IllegalArgumentException("HISTORICAL_BACKFILL_CANONICAL_TARGET is required.");
        }
        if (canonicalTarget == CanonicalTarget.DB && !dryRun && (dbUrl == null || dbUrl.isBlank())) {
            throw new IllegalArgumentException(
                "HISTORICAL_BACKFILL_DB_URL or DB_WRITER_DATABASE_URL is required when "
                    + "HISTORICAL_BACKFILL_CANONICAL_TARGET=db."
            );
        }
        if (includeCandlesticks && (seriesTicker == null || seriesTicker.isBlank())) {
            throw new IllegalArgumentException("HISTORICAL_BACKFILL_SERIES_TICKER is required for candlestick backfill.");
        }
        if (includeCandlesticks && (startTs == null || endTs == null || periodInterval == null)) {
            throw new IllegalArgumentException("HISTORICAL_BACKFILL_START_TS, END_TS, and PERIOD_INTERVAL are required for candlestick backfill.");
        }
        return this;
    }

    private static String value(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static Integer optionalInt(Map<String, String> env, String key) {
        String value = env.get(key);
        return value == null || value.isBlank() ? null : Integer.parseInt(value);
    }

    private static boolean booleanValue(Map<String, String> env, String key, boolean defaultValue) {
        String raw = value(env, key, Boolean.toString(defaultValue));
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static int positiveIntValue(Map<String, String> env, String key, int defaultValue) {
        int value = intValue(env, key, defaultValue);
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be positive: " + value);
        }
        return value;
    }

    private static int nonNegativeIntValue(Map<String, String> env, String key, int defaultValue) {
        int value = intValue(env, key, defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be zero or positive: " + value);
        }
        return value;
    }

    private static int intValue(Map<String, String> env, String key, int defaultValue) {
        return Integer.parseInt(value(env, key, Integer.toString(defaultValue)));
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(value -> value.trim().toUpperCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }
}
