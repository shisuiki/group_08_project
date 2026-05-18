package edu.illinois.group8.replay.raw;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RawIngressReplayConfig(
    String source,
    Path localRawRecordingRoot,
    String databaseUrl,
    String databaseUser,
    String databasePassword,
    String rawTable,
    String rawPayloadColumn,
    String receiveTsNsColumn,
    String connectionIdColumn,
    String sequenceColumn,
    String rawEventIdColumn,
    String marketTickerColumn,
    RawReplayMode mode,
    double speedMultiplier,
    long fixedRatePerSecond,
    long maxEvents,
    Long startReceiveTsNs,
    Long endReceiveTsNs,
    List<String> marketTickers,
    List<String> rawEventIds,
    boolean requireBoundedSelection,
    boolean dryRun,
    String replayId
) {
    public static final String DEFAULT_RAW_TABLE = "raw_ws_events";
    public static final String DEFAULT_SEQUENCE_COLUMN = "connection_sequence";

    public RawIngressReplayConfig {
        source = blankToDefault(source, "timescale");
        databaseUrl = blankToDefault(databaseUrl, "");
        databaseUser = blankToDefault(databaseUser, "");
        databasePassword = blankToDefault(databasePassword, "");
        rawTable = blankToDefault(rawTable, DEFAULT_RAW_TABLE);
        rawPayloadColumn = blankToDefault(rawPayloadColumn, "raw_payload");
        receiveTsNsColumn = blankToDefault(receiveTsNsColumn, "receive_ts_ns");
        connectionIdColumn = blankToDefault(connectionIdColumn, "connection_id");
        sequenceColumn = blankToDefault(sequenceColumn, DEFAULT_SEQUENCE_COLUMN);
        rawEventIdColumn = blankToDefault(rawEventIdColumn, "raw_event_id");
        marketTickerColumn = blankToDefault(marketTickerColumn, "market_ticker");
        mode = mode == null ? RawReplayMode.AS_FAST_AS_POSSIBLE : mode;
        if (speedMultiplier <= 0.0) {
            throw new IllegalArgumentException("RAW_REPLAY_SPEED_MULTIPLIER must be positive");
        }
        marketTickers = copyList(marketTickers);
        rawEventIds = copyList(rawEventIds);
        replayId = blankToDefault(replayId, "raw-replay-" + UUID.randomUUID());
    }

    public static RawIngressReplayConfig fromEnvironment() {
        return from(System.getenv());
    }

    static RawIngressReplayConfig from(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        String baseDir = value(env, "BASE_DIR", "/app");
        return new RawIngressReplayConfig(
            value(env, "RAW_REPLAY_SOURCE", "timescale"),
            Path.of(value(env, "RAW_REPLAY_LOCAL_ROOT", baseDir + "/recordings/raw-ingest")),
            firstValue(env, "", "RAW_REPLAY_DATABASE_URL", "RAW_REPLAY_DB_URL", "TIMESCALEDB_URL"),
            firstValue(env, "", "RAW_REPLAY_DATABASE_USER", "RAW_REPLAY_DB_USER", "TIMESCALEDB_USER"),
            firstValue(env, "", "RAW_REPLAY_DATABASE_PASSWORD", "RAW_REPLAY_DB_PASSWORD", "TIMESCALEDB_PASSWORD"),
            value(env, "RAW_REPLAY_TABLE", DEFAULT_RAW_TABLE),
            value(env, "RAW_REPLAY_RAW_PAYLOAD_COLUMN", "raw_payload"),
            value(env, "RAW_REPLAY_RECEIVE_TS_NS_COLUMN", "receive_ts_ns"),
            value(env, "RAW_REPLAY_CONNECTION_ID_COLUMN", "connection_id"),
            value(env, "RAW_REPLAY_SEQUENCE_COLUMN", DEFAULT_SEQUENCE_COLUMN),
            value(env, "RAW_REPLAY_RAW_EVENT_ID_COLUMN", "raw_event_id"),
            value(env, "RAW_REPLAY_MARKET_TICKER_COLUMN", "market_ticker"),
            mode(value(env, "RAW_REPLAY_MODE", "as-fast-as-possible")),
            doubleValue(env, "RAW_REPLAY_SPEED_MULTIPLIER", 1.0),
            longValue(env, "RAW_REPLAY_FIXED_RATE_PER_SECOND", 0L),
            longValue(env, "RAW_REPLAY_MAX_EVENTS", 0L),
            longOrNull(env, "RAW_REPLAY_START_RECEIVE_TS_NS"),
            longOrNull(env, "RAW_REPLAY_END_RECEIVE_TS_NS"),
            listValue(env, "RAW_REPLAY_MARKET_TICKERS"),
            listValue(env, "RAW_REPLAY_RAW_EVENT_IDS"),
            booleanValue(env, "RAW_REPLAY_REQUIRE_BOUNDED_SELECTION", true),
            booleanValue(env, "RAW_REPLAY_DRY_RUN", false),
            value(env, "RAW_REPLAY_ID", "raw-replay-" + UUID.randomUUID())
        );
    }

    public RawIngressReplayConfig withCliArgs(String[] args) {
        String selectedSource = source;
        Path selectedLocalRoot = localRawRecordingRoot;
        String selectedDatabaseUrl = databaseUrl;
        String selectedDatabaseUser = databaseUser;
        String selectedDatabasePassword = databasePassword;
        String selectedRawTable = rawTable;
        String selectedRawPayloadColumn = rawPayloadColumn;
        String selectedReceiveTsNsColumn = receiveTsNsColumn;
        String selectedConnectionIdColumn = connectionIdColumn;
        String selectedSequenceColumn = sequenceColumn;
        String selectedRawEventIdColumn = rawEventIdColumn;
        String selectedMarketTickerColumn = marketTickerColumn;
        RawReplayMode selectedMode = mode;
        double selectedSpeed = speedMultiplier;
        long selectedFixedRate = fixedRatePerSecond;
        long selectedMaxEvents = maxEvents;
        Long selectedStartReceiveTsNs = startReceiveTsNs;
        Long selectedEndReceiveTsNs = endReceiveTsNs;
        List<String> selectedMarketTickers = marketTickers;
        List<String> selectedRawEventIds = rawEventIds;
        boolean selectedRequireBoundedSelection = requireBoundedSelection;
        boolean selectedDryRun = dryRun;
        String selectedReplayId = replayId;
        for (String arg : args) {
            if (arg.startsWith("--source=")) {
                selectedSource = arg.substring("--source=".length());
            } else if (arg.startsWith("--local-root=")) {
                selectedLocalRoot = Path.of(arg.substring("--local-root=".length()));
            } else if (arg.startsWith("--db-url=")) {
                selectedDatabaseUrl = arg.substring("--db-url=".length());
            } else if (arg.startsWith("--db-user=")) {
                selectedDatabaseUser = arg.substring("--db-user=".length());
            } else if (arg.startsWith("--db-password=")) {
                selectedDatabasePassword = arg.substring("--db-password=".length());
            } else if (arg.startsWith("--table=")) {
                selectedRawTable = arg.substring("--table=".length());
            } else if (arg.startsWith("--raw-payload-column=")) {
                selectedRawPayloadColumn = arg.substring("--raw-payload-column=".length());
            } else if (arg.startsWith("--receive-ts-ns-column=")) {
                selectedReceiveTsNsColumn = arg.substring("--receive-ts-ns-column=".length());
            } else if (arg.startsWith("--connection-id-column=")) {
                selectedConnectionIdColumn = arg.substring("--connection-id-column=".length());
            } else if (arg.startsWith("--sequence-column=")) {
                selectedSequenceColumn = arg.substring("--sequence-column=".length());
            } else if (arg.startsWith("--raw-event-id-column=")) {
                selectedRawEventIdColumn = arg.substring("--raw-event-id-column=".length());
            } else if (arg.startsWith("--market-ticker-column=")) {
                selectedMarketTickerColumn = arg.substring("--market-ticker-column=".length());
            } else if (arg.startsWith("--market-tickers=")) {
                selectedMarketTickers = parseList(arg.substring("--market-tickers=".length()));
            } else if (arg.startsWith("--raw-event-ids=")) {
                selectedRawEventIds = parseList(arg.substring("--raw-event-ids=".length()));
            } else if (arg.startsWith("--start-receive-ts-ns=")) {
                selectedStartReceiveTsNs = parseLongOrNull(arg.substring("--start-receive-ts-ns=".length()));
            } else if (arg.startsWith("--end-receive-ts-ns=")) {
                selectedEndReceiveTsNs = parseLongOrNull(arg.substring("--end-receive-ts-ns=".length()));
            } else if (arg.startsWith("--mode=")) {
                selectedMode = mode(arg.substring("--mode=".length()));
            } else if (arg.startsWith("--speed=")) {
                selectedSpeed = Double.parseDouble(arg.substring("--speed=".length()));
            } else if (arg.startsWith("--fixed-rate=")) {
                selectedFixedRate = Long.parseLong(arg.substring("--fixed-rate=".length()));
                selectedMode = RawReplayMode.FIXED_RATE;
            } else if (arg.startsWith("--max-events=")) {
                selectedMaxEvents = Long.parseLong(arg.substring("--max-events=".length()));
            } else if (arg.equals("--dry-run")) {
                selectedDryRun = true;
            } else if (arg.equals("--allow-unbounded-selection")) {
                selectedRequireBoundedSelection = false;
            } else if (arg.startsWith("--replay-id=")) {
                selectedReplayId = arg.substring("--replay-id=".length());
            } else if (arg.equals("--help") || arg.equals("-h")) {
                throw new IllegalArgumentException(usage());
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("Unknown RawIngressReplayCli option: " + arg + "\n\n" + usage());
            }
        }
        return new RawIngressReplayConfig(
            selectedSource,
            selectedLocalRoot,
            selectedDatabaseUrl,
            selectedDatabaseUser,
            selectedDatabasePassword,
            selectedRawTable,
            selectedRawPayloadColumn,
            selectedReceiveTsNsColumn,
            selectedConnectionIdColumn,
            selectedSequenceColumn,
            selectedRawEventIdColumn,
            selectedMarketTickerColumn,
            selectedMode,
            selectedSpeed,
            selectedFixedRate,
            selectedMaxEvents,
            selectedStartReceiveTsNs,
            selectedEndReceiveTsNs,
            selectedMarketTickers,
            selectedRawEventIds,
            selectedRequireBoundedSelection,
            selectedDryRun,
            selectedReplayId
        );
    }

    public RawReplaySelection selection() {
        return new RawReplaySelection(
            startReceiveTsNs,
            endReceiveTsNs,
            marketTickers,
            rawEventIds,
            maxEvents
        );
    }

    public void validateForReplay() {
        String normalizedSource = source.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (("timescale".equals(normalizedSource)
            || "timescaledb".equals(normalizedSource)
            || "postgres".equals(normalizedSource)
            || "postgresql".equals(normalizedSource))
            && databaseUrl.isBlank()) {
            throw new IllegalArgumentException("RAW_REPLAY_DATABASE_URL is required when RAW_REPLAY_SOURCE=timescale");
        }
        if (requireBoundedSelection && selection().isUnbounded()) {
            throw new IllegalArgumentException(
                "Raw replay selection is unbounded. Set a time window, market tickers, raw event ids, "
                    + "--max-events, or pass --allow-unbounded-selection deliberately."
            );
        }
    }

    public static String usage() {
        return """
            Usage: RawIngressReplayCli [options]

            Storage source:
              --source=timescale|local-ndjson
              --db-url=jdbc:postgresql://host:5432/db
              --db-user=user
              --db-password=password
              --table=raw_ws_events
              --sequence-column=connection_sequence
              --local-root=/path/to/raw-ingest

            Exact selection:
              --start-receive-ts-ns=...
              --end-receive-ts-ns=...
              --market-tickers=TICKER1,TICKER2
              --raw-event-ids=raw_abc,raw_def
              --max-events=1000
              --allow-unbounded-selection

            Replay pacing:
              --mode=as-fast-as-possible|original-timestamps|fixed-rate
              --speed=1.0
              --fixed-rate=50000
              --dry-run
              --replay-id=id
            """;
    }

    private static String value(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String firstValue(Map<String, String> env, String defaultValue, String... keys) {
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return defaultValue;
    }

    private static boolean booleanValue(Map<String, String> env, String key, boolean defaultValue) {
        String raw = value(env, key, Boolean.toString(defaultValue));
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static long longValue(Map<String, String> env, String key, long defaultValue) {
        String raw = value(env, key, Long.toString(defaultValue));
        return Long.parseLong(raw);
    }

    private static Long longOrNull(Map<String, String> env, String key) {
        return parseLongOrNull(env.get(key));
    }

    private static Long parseLongOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
    }

    private static double doubleValue(Map<String, String> env, String key, double defaultValue) {
        String raw = value(env, key, Double.toString(defaultValue));
        return Double.parseDouble(raw);
    }

    private static List<String> listValue(Map<String, String> env, String key) {
        return parseList(env.get(key));
    }

    private static List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();
    }

    private static RawReplayMode mode(String raw) {
        return RawReplayMode.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    private static List<String> copyList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
