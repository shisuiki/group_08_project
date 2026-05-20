package edu.illinois.group8.frontend;

import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public record FrontendAdapterConfig(
    String host,
    int port,
    SourceMode sourceMode,
    FeatureSource featureSource,
    String aeronChannel,
    Path recordingRoot,
    Path staticRoot,
    List<StreamContract> streams,
    List<String> moduleNames,
    int maxFeaturesPerMarket,
    int maxSymbolsIndexed,
    int fragmentLimit,
    int idleSleepMillis,
    long recordingMaxEvents,
    int featureOutputMaxRows,
    boolean featureOutputRefreshEnabled,
    int featureOutputRefreshIntervalMs,
    int featureOutputRefreshMaxRows,
    MetadataSource metadataSource,
    int metadataMaxRows,
    SemanticMetadataStatusSource semanticMetadataStatusSource,
    String llmMetadataModel,
    String llmMetadataFallbackModel,
    String llmMetadataTaxonomyVersion,
    boolean includeSmokeMarkets,
    String dbUrl,
    String dbUser,
    String dbPassword,
    boolean dbIncludeReplayEvents,
    String dbReplayId,
    String featurePlantCursorName,
    boolean operatorControlEnabled,
    String basicAuthUser,
    String basicAuthPassword
) {
    private static final int DEFAULT_FEATURE_OUTPUT_MAX_ROWS = 10_000;
    private static final int DEFAULT_FEATURE_OUTPUT_REFRESH_INTERVAL_MS = 1_000;
    private static final int DEFAULT_LATEST_MARKET_STATE_REFRESH_INTERVAL_MS = 250;
    private static final int DEFAULT_METADATA_MAX_ROWS = 1_000;

    public enum SourceMode {
        AERON, RECORDING, DB;

        public static SourceMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DB;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "aeron", "live", "tickerplant" -> AERON;
                case "recording", "history", "storage" -> RECORDING;
                case "db", "postgres", "postgresql", "timescale", "timescaledb" -> DB;
                default -> throw new IllegalArgumentException("Unknown FRONTEND_ADAPTER_SOURCE: " + raw);
            };
        }
    }

    public enum FeatureSource {
        MODULES, FEATURE_OUTPUTS, LATEST_MARKET_STATE;

        public static FeatureSource parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return MODULES;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "modules", "feature_modules" -> MODULES;
                case "feature_outputs", "db_features", "persisted" -> FEATURE_OUTPUTS;
                case "latest_state", "latest_market_state" -> LATEST_MARKET_STATE;
                default -> throw new IllegalArgumentException("Unknown FRONTEND_ADAPTER_FEATURE_SOURCE: " + raw);
            };
        }

        public boolean dbBacked() {
            return this == FEATURE_OUTPUTS || this == LATEST_MARKET_STATE;
        }
    }

    public enum MetadataSource {
        AUTO, DB, DISABLED;

        public static MetadataSource parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "auto", "automatic" -> AUTO;
                case "db", "postgres", "postgresql", "timescale", "timescaledb", "market_metadata" -> DB;
                case "disabled", "off", "none" -> DISABLED;
                default -> throw new IllegalArgumentException("Unknown FRONTEND_ADAPTER_METADATA_SOURCE: " + raw);
            };
        }
    }

    public enum SemanticMetadataStatusSource {
        AUTO, DB, DISABLED;

        public static SemanticMetadataStatusSource parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "auto", "automatic" -> AUTO;
                case "db", "postgres", "postgresql", "timescale", "timescaledb", "semantic_metadata" -> DB;
                case "disabled", "off", "none" -> DISABLED;
                default -> throw new IllegalArgumentException(
                    "Unknown FRONTEND_ADAPTER_SEMANTIC_METADATA_STATUS_SOURCE: " + raw
                );
            };
        }
    }

    public FrontendAdapterConfig {
        host = host == null || host.isBlank() ? "127.0.0.1" : host;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("FRONTEND_ADAPTER_PORT out of range: " + port);
        }
        if (sourceMode == null) {
            throw new IllegalArgumentException("sourceMode is required");
        }
        if (featureSource == null) {
            throw new IllegalArgumentException("featureSource is required");
        }
        if (metadataSource == null) {
            throw new IllegalArgumentException("metadataSource is required");
        }
        if (semanticMetadataStatusSource == null) {
            throw new IllegalArgumentException("semanticMetadataStatusSource is required");
        }
        recordingRoot = recordingRoot == null ? Path.of("recordings") : recordingRoot;
        staticRoot = staticRoot == null ? Path.of("frontend/tradingview-lightweight") : staticRoot;
        streams = List.copyOf(streams);
        moduleNames = List.copyOf(moduleNames);
        if (maxFeaturesPerMarket < 1) {
            throw new IllegalArgumentException("maxFeaturesPerMarket must be positive");
        }
        if (maxSymbolsIndexed < 1) {
            throw new IllegalArgumentException("maxSymbolsIndexed must be positive");
        }
        if (fragmentLimit < 1) {
            throw new IllegalArgumentException("fragmentLimit must be positive");
        }
        if (idleSleepMillis < 0) {
            throw new IllegalArgumentException("idleSleepMillis must be non-negative");
        }
        if (featureOutputMaxRows < 1) {
            throw new IllegalArgumentException("featureOutputMaxRows must be positive");
        }
        if (featureOutputRefreshIntervalMs < 1) {
            throw new IllegalArgumentException("featureOutputRefreshIntervalMs must be positive");
        }
        if (featureOutputRefreshMaxRows < 1) {
            throw new IllegalArgumentException("featureOutputRefreshMaxRows must be positive");
        }
        if (metadataMaxRows < 1) {
            throw new IllegalArgumentException("metadataMaxRows must be positive");
        }
        llmMetadataModel = normalize(llmMetadataModel);
        llmMetadataFallbackModel = normalize(llmMetadataFallbackModel);
        llmMetadataTaxonomyVersion = normalize(llmMetadataTaxonomyVersion);
        dbUrl = normalize(dbUrl);
        dbUser = normalize(dbUser);
        dbPassword = dbPassword == null ? "" : dbPassword;
        dbReplayId = normalize(dbReplayId);
        featurePlantCursorName = normalize(featurePlantCursorName);
        basicAuthUser = normalize(basicAuthUser);
        basicAuthPassword = basicAuthPassword == null ? "" : basicAuthPassword;
    }

    public boolean basicAuthEnabled() {
        return !basicAuthUser.isBlank() && !basicAuthPassword.isBlank();
    }

    public FrontendAdapterConfig(
        String host,
        int port,
        SourceMode sourceMode,
        String aeronChannel,
        Path recordingRoot,
        List<StreamContract> streams,
        List<String> moduleNames,
        int maxFeaturesPerMarket,
        int maxSymbolsIndexed,
        int fragmentLimit,
        int idleSleepMillis,
        long recordingMaxEvents,
        String dbUrl,
        String dbUser,
        String dbPassword,
        boolean dbIncludeReplayEvents,
        String dbReplayId
    ) {
        this(
            host,
            port,
            sourceMode,
            FeatureSource.MODULES,
            aeronChannel,
            recordingRoot,
            Path.of("frontend/tradingview-lightweight"),
            streams,
            moduleNames,
            maxFeaturesPerMarket,
            maxSymbolsIndexed,
            fragmentLimit,
            idleSleepMillis,
            recordingMaxEvents,
            DEFAULT_FEATURE_OUTPUT_MAX_ROWS,
            false,
            DEFAULT_FEATURE_OUTPUT_REFRESH_INTERVAL_MS,
            DEFAULT_FEATURE_OUTPUT_MAX_ROWS,
            MetadataSource.AUTO,
            DEFAULT_METADATA_MAX_ROWS,
            SemanticMetadataStatusSource.AUTO,
            "",
            "",
            "",
            false,
            dbUrl,
            dbUser,
            dbPassword,
            dbIncludeReplayEvents,
            dbReplayId,
            "",
            false,
            "",
            ""
        );
    }

    public static FrontendAdapterConfig fromEnvironment() {
        return from(System.getenv());
    }

    public static FrontendAdapterConfig from(Map<String, String> env) {
        String baseDir = value(env, "BASE_DIR", "/app");
        SourceMode sourceMode = SourceMode.parse(value(env, "FRONTEND_ADAPTER_SOURCE", "db"));
        FeatureSource featureSource = FeatureSource.parse(value(env, "FRONTEND_ADAPTER_FEATURE_SOURCE", "modules"));
        int featureOutputMaxRows = intValue(
            env,
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS",
            DEFAULT_FEATURE_OUTPUT_MAX_ROWS
        );
        return new FrontendAdapterConfig(
            value(env, "FRONTEND_ADAPTER_HOST", "127.0.0.1"),
            intValue(env, "FRONTEND_ADAPTER_PORT", 8090),
            sourceMode,
            featureSource,
            value(env, "FRONTEND_ADAPTER_AERON_CHANNEL",
                value(env, "AERON_EXTERNAL_CHANNEL", "aeron:udp?endpoint=224.0.1.1:40456")),
            Path.of(value(env, "FRONTEND_ADAPTER_RECORDING_ROOT", baseDir + "/recordings")),
            Path.of(value(env, "FRONTEND_ADAPTER_STATIC_ROOT", "frontend/tradingview-lightweight")),
            resolveStreams(value(env, "FRONTEND_ADAPTER_STREAMS",
                "canonical.trade,canonical.ticker,derived.top_of_book")),
            csv(value(env, "FRONTEND_ADAPTER_MODULES", "bbo,ticker_snapshot,trade_tape")),
            intValue(env, "FRONTEND_ADAPTER_MAX_FEATURES_PER_MARKET", 10_000),
            intValue(env, "FRONTEND_ADAPTER_MAX_SYMBOLS_INDEXED", 5_000),
            intValue(env, "FRONTEND_ADAPTER_FRAGMENT_LIMIT", 64),
            intValue(env, "FRONTEND_ADAPTER_IDLE_SLEEP_MS", 1),
            longValue(env, "FRONTEND_ADAPTER_RECORDING_MAX_EVENTS", 0L),
            featureOutputMaxRows,
            booleanValue(
                env,
                "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_ENABLED",
                featureSource.dbBacked()
            ),
            intValue(
                env,
                "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS",
                featureSource == FeatureSource.LATEST_MARKET_STATE
                    ? DEFAULT_LATEST_MARKET_STATE_REFRESH_INTERVAL_MS
                    : DEFAULT_FEATURE_OUTPUT_REFRESH_INTERVAL_MS
            ),
            intValue(
                env,
                "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS",
                featureOutputMaxRows
            ),
            MetadataSource.parse(value(env, "FRONTEND_ADAPTER_METADATA_SOURCE", "auto")),
            intValue(env, "FRONTEND_ADAPTER_METADATA_MAX_ROWS", DEFAULT_METADATA_MAX_ROWS),
            SemanticMetadataStatusSource.parse(value(env, "FRONTEND_ADAPTER_SEMANTIC_METADATA_STATUS_SOURCE", "auto")),
            value(env, "LLM_METADATA_MODEL", "deepseek/deepseek-v4-flash:free"),
            value(env, "LLM_METADATA_FALLBACK_MODEL", "deepseek/deepseek-v4-flash"),
            value(env, "LLM_METADATA_TAXONOMY_VERSION", "v1"),
            booleanValue(env, "FRONTEND_ADAPTER_INCLUDE_SMOKE_MARKETS", false),
            value(env, "FRONTEND_ADAPTER_DB_URL", value(env, "DB_WRITER_DATABASE_URL", "")),
            value(env, "FRONTEND_ADAPTER_DB_USER", value(env, "DB_WRITER_DATABASE_USER", "")),
            value(env, "FRONTEND_ADAPTER_DB_PASSWORD", value(env, "DB_WRITER_DATABASE_PASSWORD", "")),
            Boolean.parseBoolean(value(env, "FRONTEND_ADAPTER_DB_INCLUDE_REPLAY", "false")),
            value(env, "FRONTEND_ADAPTER_DB_REPLAY_ID", ""),
            value(env, "FRONTEND_ADAPTER_FEATUREPLANT_CURSOR_NAME",
                value(env, "FEATUREPLANT_DB_CURSOR_NAME", "")),
            booleanValue(env, "FRONTEND_ADAPTER_OPERATOR_CONTROL_ENABLED", false),
            value(env, "FRONTEND_ADAPTER_BASIC_AUTH_USER", ""),
            value(env, "FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD", "")
        );
    }

    private static List<StreamContract> resolveStreams(String raw) {
        List<StreamContract> resolved = new java.util.ArrayList<>();
        for (String streamName : csv(raw)) {
            Optional<StreamContract> stream = StreamRegistry.byName(streamName);
            if (stream.isEmpty()) {
                throw new IllegalArgumentException("Unknown frontend-adapter stream: " + streamName);
            }
            resolved.add(stream.get());
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("FRONTEND_ADAPTER_STREAMS must include at least one stream.");
        }
        return List.copyOf(resolved);
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private static String value(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static int intValue(Map<String, String> env, String key, int defaultValue) {
        return Integer.parseInt(value(env, key, Integer.toString(defaultValue)));
    }

    private static long longValue(Map<String, String> env, String key, long defaultValue) {
        return Long.parseLong(value(env, key, Long.toString(defaultValue)));
    }

    private static boolean booleanValue(Map<String, String> env, String key, boolean defaultValue) {
        return Boolean.parseBoolean(value(env, key, Boolean.toString(defaultValue)));
    }
}
