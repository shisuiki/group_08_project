package edu.illinois.group8.feature;

import edu.illinois.group8.KalshiMetricsServer;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.JdbcCanonicalEventReader;
import edu.illinois.group8.storage.db.JdbcConnectionFactories;
import edu.illinois.group8.storage.db.JdbcConnectionFactory;
import edu.illinois.group8.storage.db.JdbcFeatureOutputProjectionStore;
import edu.illinois.group8.storage.db.JdbcFeatureOutputStore;
import edu.illinois.group8.storage.db.JdbcFeaturePlantCursorStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class FeaturePlantCli {
    private FeaturePlantCli() {
    }

    public static void main(String[] args) {
        Config config = Config.fromEnvironment().withArgs(args);
        BackendMetrics metrics = new BackendMetrics();
        try (MetricsServerHandle ignored = config.metricsServer(metrics)) {
            if (config.usesTransactionalDbProjector()) {
                try (FeaturePlantDbProjector projector = config.dbProjector(metrics)) {
                    if (config.runOnce()) {
                        long consumed = projector.runUntilExhausted(config.batchSize());
                        System.err.println("FeaturePlant projected " + consumed + " canonical DB events");
                        System.err.print(metrics.prometheusText());
                        return;
                    }
                    while (!Thread.currentThread().isInterrupted()) {
                        int polled = projector.poll(config.batchSize());
                        if (polled == 0) {
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(config.idleSleepMillis()));
                        }
                    }
                }
                return;
            }

            try (
                 FeatureOutputSink sink = config.outputSink(metrics);
                 CanonicalEnvelopeSource source = config.source();
                 FeaturePlantService service = new FeaturePlantService(source, config.modules(), sink, metrics)) {
                if (config.runOnce()) {
                    long consumed = service.runUntilExhausted(config.batchSize());
                    System.err.println("FeaturePlant consumed " + consumed + " canonical events");
                    System.err.print(service.metricsText());
                    return;
                }
                while (!Thread.currentThread().isInterrupted()) {
                    int polled = service.poll(config.batchSize());
                    if (polled == 0) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(config.idleSleepMillis()));
                    }
                }
            }
        }
    }

    record Config(
        String sourceMode,
        Path recordingRoot,
        String aeronChannel,
        List<StreamContract> streams,
        List<FeatureModule> modules,
        long maxEvents,
        int batchSize,
        int idleSleepMillis,
        boolean runOnce,
        String metricsHost,
        int metricsPort,
        String outputMode,
        String dbUrl,
        String dbUser,
        String dbPassword,
        boolean dbIncludeReplayEvents,
        String dbReplayId,
        String dbCursorName,
        boolean dbOutputAsyncEnabled,
        int dbOutputQueueCapacity,
        int dbOutputBatchSize,
        long dbOutputCloseTimeoutMs
    ) {
        static Config fromEnvironment() {
            return from(System.getenv());
        }

        static Config from(Map<String, String> env) {
            Objects.requireNonNull(env, "env");
            String baseDir = value(env, "BASE_DIR", "/app");
            return new Config(
                value(env, "FEATUREPLANT_SOURCE", "db"),
                Path.of(value(env, "FEATUREPLANT_RECORDING_ROOT", baseDir + "/recordings")),
                value(env, "FEATUREPLANT_AERON_CHANNEL",
                    value(env, "AERON_EXTERNAL_CHANNEL", "aeron:udp?endpoint=224.0.1.1:40456")),
                resolveStreams(value(env, "FEATUREPLANT_STREAMS",
                    "canonical.trade,canonical.ticker,derived.top_of_book")),
                resolveModules(value(env, "FEATUREPLANT_MODULES", "bbo,ticker_snapshot,trade_tape")),
                longValue(env, "FEATUREPLANT_MAX_EVENTS", 0L),
                intValue(env, "FEATUREPLANT_BATCH_SIZE", 100),
                intValue(env, "FEATUREPLANT_IDLE_SLEEP_MS", 1),
                Boolean.parseBoolean(value(env, "FEATUREPLANT_RUN_ONCE", "true")),
                value(env, "FEATUREPLANT_METRICS_HOST", "0.0.0.0"),
                nonNegativeIntValue(env, "FEATUREPLANT_METRICS_PORT", 0),
                value(env, "FEATUREPLANT_OUTPUT", "stdout"),
                value(env, "FEATUREPLANT_DB_URL", value(env, "DB_WRITER_DATABASE_URL", "")),
                value(env, "FEATUREPLANT_DB_USER", value(env, "DB_WRITER_DATABASE_USER", "")),
                value(env, "FEATUREPLANT_DB_PASSWORD", value(env, "DB_WRITER_DATABASE_PASSWORD", "")),
                Boolean.parseBoolean(value(env, "FEATUREPLANT_DB_INCLUDE_REPLAY", "false")),
                value(env, "FEATUREPLANT_DB_REPLAY_ID", ""),
                value(env, "FEATUREPLANT_DB_CURSOR_NAME", ""),
                booleanValue(env, "FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED", false),
                positiveIntValue(
                    env,
                    "FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY",
                    value(env, "DB_WRITER_QUEUE_CAPACITY",
                        Integer.toString(BoundedAsyncFeatureOutputSink.DEFAULT_QUEUE_CAPACITY))
                ),
                positiveIntValue(
                    env,
                    "FEATUREPLANT_DB_OUTPUT_BATCH_SIZE",
                    value(env, "DB_WRITER_BATCH_SIZE",
                        Integer.toString(BoundedAsyncFeatureOutputSink.DEFAULT_BATCH_SIZE))
                ),
                nonNegativeLongValue(
                    env,
                    "FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS",
                    BoundedAsyncFeatureOutputSink.DEFAULT_CLOSE_TIMEOUT_MS
                )
            );
        }

        Config withArgs(String[] args) {
            String nextSourceMode = sourceMode;
            Path nextRecordingRoot = recordingRoot;
            String nextAeronChannel = aeronChannel;
            List<StreamContract> nextStreams = streams;
            List<FeatureModule> nextModules = modules;
            long nextMaxEvents = maxEvents;
            int nextBatchSize = batchSize;
            int nextIdleSleepMillis = idleSleepMillis;
            boolean nextRunOnce = runOnce;
            String nextMetricsHost = metricsHost;
            int nextMetricsPort = metricsPort;
            String nextOutputMode = outputMode;
            String nextDbUrl = dbUrl;
            String nextDbUser = dbUser;
            String nextDbPassword = dbPassword;
            boolean nextDbIncludeReplayEvents = dbIncludeReplayEvents;
            String nextDbReplayId = dbReplayId;
            String nextDbCursorName = dbCursorName;
            boolean nextDbOutputAsyncEnabled = dbOutputAsyncEnabled;
            int nextDbOutputQueueCapacity = dbOutputQueueCapacity;
            int nextDbOutputBatchSize = dbOutputBatchSize;
            long nextDbOutputCloseTimeoutMs = dbOutputCloseTimeoutMs;

            for (String arg : args) {
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsageAndExit();
                } else if (arg.startsWith("--source=")) {
                    nextSourceMode = arg.substring("--source=".length());
                } else if (arg.startsWith("--root=")) {
                    nextRecordingRoot = Path.of(arg.substring("--root=".length()));
                } else if (arg.startsWith("--channel=")) {
                    nextAeronChannel = arg.substring("--channel=".length());
                } else if (arg.startsWith("--streams=")) {
                    nextStreams = resolveStreams(arg.substring("--streams=".length()));
                } else if (arg.startsWith("--modules=")) {
                    nextModules = resolveModules(arg.substring("--modules=".length()));
                } else if (arg.startsWith("--max-events=")) {
                    nextMaxEvents = Long.parseLong(arg.substring("--max-events=".length()));
                } else if (arg.startsWith("--batch-size=")) {
                    nextBatchSize = Integer.parseInt(arg.substring("--batch-size=".length()));
                } else if (arg.startsWith("--idle-sleep-ms=")) {
                    nextIdleSleepMillis = Integer.parseInt(arg.substring("--idle-sleep-ms=".length()));
                } else if (arg.startsWith("--metrics-host=")) {
                    nextMetricsHost = arg.substring("--metrics-host=".length());
                } else if (arg.startsWith("--metrics-port=")) {
                    nextMetricsPort = parseNonNegativeInt(
                        arg.substring("--metrics-port=".length()),
                        "FEATUREPLANT_METRICS_PORT"
                    );
                } else if (arg.startsWith("--output=")) {
                    nextOutputMode = arg.substring("--output=".length());
                } else if (arg.startsWith("--db-url=")) {
                    nextDbUrl = arg.substring("--db-url=".length());
                } else if (arg.startsWith("--db-user=")) {
                    nextDbUser = arg.substring("--db-user=".length());
                } else if (arg.startsWith("--db-password=")) {
                    nextDbPassword = arg.substring("--db-password=".length());
                } else if ("--include-replay".equals(arg)) {
                    nextDbIncludeReplayEvents = true;
                } else if (arg.startsWith("--replay-id=")) {
                    nextDbReplayId = arg.substring("--replay-id=".length());
                } else if (arg.startsWith("--db-cursor-name=")) {
                    nextDbCursorName = arg.substring("--db-cursor-name=".length());
                } else if ("--db-output-async".equals(arg)) {
                    nextDbOutputAsyncEnabled = true;
                } else if ("--db-output-sync".equals(arg)) {
                    nextDbOutputAsyncEnabled = false;
                } else if (arg.startsWith("--db-output-queue-capacity=")) {
                    nextDbOutputQueueCapacity = parsePositiveInt(
                        arg.substring("--db-output-queue-capacity=".length()),
                        "FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY"
                    );
                } else if (arg.startsWith("--db-output-batch-size=")) {
                    nextDbOutputBatchSize = parsePositiveInt(
                        arg.substring("--db-output-batch-size=".length()),
                        "FEATUREPLANT_DB_OUTPUT_BATCH_SIZE"
                    );
                } else if (arg.startsWith("--db-output-close-timeout-ms=")) {
                    nextDbOutputCloseTimeoutMs = parseNonNegativeLong(
                        arg.substring("--db-output-close-timeout-ms=".length()),
                        "FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS"
                    );
                } else if ("--follow".equals(arg)) {
                    nextRunOnce = false;
                } else if ("--run-once".equals(arg)) {
                    nextRunOnce = true;
                } else {
                    throw new IllegalArgumentException("Unknown featureplant argument: " + arg);
                }
            }
            return new Config(
                nextSourceMode,
                nextRecordingRoot,
                nextAeronChannel,
                nextStreams,
                nextModules,
                nextMaxEvents,
                Math.max(1, nextBatchSize),
                Math.max(0, nextIdleSleepMillis),
                nextRunOnce,
                nextMetricsHost,
                Math.max(0, nextMetricsPort),
                nextOutputMode,
                nextDbUrl,
                nextDbUser,
                nextDbPassword,
                nextDbIncludeReplayEvents,
                nextDbReplayId,
                nextDbCursorName,
                nextDbOutputAsyncEnabled,
                Math.max(1, nextDbOutputQueueCapacity),
                Math.max(1, nextDbOutputBatchSize),
                Math.max(0L, nextDbOutputCloseTimeoutMs)
            );
        }

        MetricsServerHandle metricsServer(BackendMetrics metrics) {
            return metricsServer(metrics, Config::defaultMetricsServer);
        }

        MetricsServerHandle metricsServer(BackendMetrics metrics, MetricsServerFactory serverFactory) {
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(serverFactory, "serverFactory");
            if (metricsPort == 0) {
                return () -> {
                };
            }
            if (metricsHost == null || metricsHost.isBlank()) {
                throw new IllegalArgumentException("FEATUREPLANT_METRICS_HOST must not be blank when metrics are enabled.");
            }
            return serverFactory.start(metricsHost, metricsPort, metrics);
        }

        private static MetricsServerHandle defaultMetricsServer(String host, int port, BackendMetrics metrics) {
            KalshiMetricsServer server = KalshiMetricsServer.start(host, port, metrics);
            return server::close;
        }

        FeatureOutputSink outputSink() {
            return outputSink(new BackendMetrics());
        }

        FeatureOutputSink outputSink(BackendMetrics metrics) {
            return outputSink(metrics, Config::defaultDbOutputSink);
        }

        FeatureOutputSink outputSink(BackendMetrics metrics, DbOutputSinkFactory dbSinkFactory) {
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(dbSinkFactory, "dbSinkFactory");
            if (dbOutputAsyncEnabled && durableDbCursorOutputEnabled()) {
                throw new IllegalArgumentException(
                    "FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED=true is unsafe with a durable DB cursor; "
                        + "use the transactional DB projector or disable async output."
                );
            }
            List<FeatureOutputSink> sinks = new ArrayList<>();
            for (String mode : outputModes(outputMode)) {
                switch (mode) {
                    case "stdout", "console" -> sinks.add(new StdoutFeatureOutputSink());
                    case "db", "postgres", "postgresql", "timescale", "timescaledb" ->
                        sinks.add(dbOutputSink(dbSinkFactory, metrics));
                    default -> throw new IllegalArgumentException("Unsupported FEATUREPLANT_OUTPUT: " + mode);
                }
            }
            if (sinks.size() == 1) {
                return sinks.get(0);
            }
            return new CompositeFeatureOutputSink(sinks);
        }

        boolean usesTransactionalDbProjector() {
            List<String> modes = outputModes(outputMode);
            return isDbSourceMode()
                && modes.size() == 1
                && modes.contains("db")
                && dbCursorName != null
                && !dbCursorName.isBlank();
        }

        FeaturePlantDbProjector dbProjector(BackendMetrics metrics) {
            return dbProjector(metrics, Config::defaultDbProjector);
        }

        FeaturePlantDbProjector dbProjector(BackendMetrics metrics, DbProjectorFactory dbProjectorFactory) {
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(dbProjectorFactory, "dbProjectorFactory");
            if (!usesTransactionalDbProjector()) {
                throw new IllegalArgumentException(
                    "Transactional FeaturePlant DB projector requires FEATUREPLANT_SOURCE=db, FEATUREPLANT_OUTPUT=db, "
                        + "and FEATUREPLANT_DB_CURSOR_NAME."
                );
            }
            if (dbUrl == null || dbUrl.isBlank()) {
                throw new IllegalArgumentException(
                    "FEATUREPLANT_DB_URL or --db-url is required when FEATUREPLANT_SOURCE=db and FEATUREPLANT_OUTPUT=db"
                );
            }
            return dbProjectorFactory.create(
                dbUrl,
                dbUser,
                dbPassword,
                streams,
                modules,
                maxEvents,
                dbIncludeReplayEvents,
                dbReplayId,
                dbCursorName,
                metrics
            );
        }

        private FeatureOutputSink dbOutputSink(DbOutputSinkFactory dbSinkFactory, BackendMetrics metrics) {
            if (dbUrl == null || dbUrl.isBlank()) {
                throw new IllegalArgumentException(
                    "FEATUREPLANT_DB_URL or --db-url is required when FEATUREPLANT_OUTPUT includes db"
                );
            }
            return dbSinkFactory.create(
                dbUrl,
                dbUser,
                dbPassword,
                dbOutputAsyncEnabled,
                dbOutputQueueCapacity,
                dbOutputBatchSize,
                dbOutputCloseTimeoutMs,
                metrics
            );
        }

        private static FeatureOutputSink defaultDbOutputSink(
            String dbUrl,
            String dbUser,
            String dbPassword,
            boolean asyncEnabled,
            int queueCapacity,
            int batchSize,
            long closeTimeoutMs,
            BackendMetrics metrics
        ) {
            JdbcFeatureOutputStore store = JdbcFeatureOutputStore.fromDriverManager(dbUrl, dbUser, dbPassword);
            if (!asyncEnabled) {
                return new DbFeatureOutputSink(store);
            }
            return new BoundedAsyncFeatureOutputSink(store, metrics, queueCapacity, batchSize, closeTimeoutMs);
        }

        private static FeaturePlantDbProjector defaultDbProjector(
            String dbUrl,
            String dbUser,
            String dbPassword,
            List<StreamContract> streams,
            List<FeatureModule> modules,
            long maxEvents,
            boolean includeReplayEvents,
            String replayId,
            String cursorName,
            BackendMetrics metrics
        ) {
            JdbcConnectionFactory connectionFactory = JdbcConnectionFactories.fromDriverManager(dbUrl, dbUser, dbPassword);
            return new FeaturePlantDbProjector(
                new JdbcCanonicalEventReader(connectionFactory),
                new JdbcFeatureOutputProjectionStore(connectionFactory),
                streams,
                modules,
                maxEvents,
                includeReplayEvents,
                replayId,
                cursorName,
                metrics
            );
        }

        CanonicalEnvelopeSource source() {
            return switch (sourceMode.trim().toLowerCase(Locale.ROOT)) {
                case "recording", "history", "storage" ->
                    RecordingCanonicalEnvelopeSource.fromRoot(recordingRoot, streams, maxEvents);
                case "aeron", "live", "tickerplant" -> new AeronCanonicalEnvelopeSource(aeronChannel, streams);
                case "db", "postgres", "postgresql", "timescale", "timescaledb" -> dbSource();
                default -> throw new IllegalArgumentException("Unsupported FEATUREPLANT_SOURCE: " + sourceMode);
            };
        }

        private CanonicalEnvelopeSource dbSource() {
            if (dbUrl == null || dbUrl.isBlank()) {
                throw new IllegalArgumentException(
                    "FEATUREPLANT_DB_URL or --db-url is required when FEATUREPLANT_SOURCE=db"
                );
            }
            return new DbCanonicalEnvelopeSource(
                new JdbcCanonicalEventReader(JdbcConnectionFactories.fromDriverManager(dbUrl, dbUser, dbPassword)),
                streams,
                maxEvents,
                dbIncludeReplayEvents,
                dbReplayId,
                dbCursorName == null || dbCursorName.isBlank()
                    ? null
                    : JdbcFeaturePlantCursorStore.fromDriverManager(dbUrl, dbUser, dbPassword),
                dbCursorName
            );
        }

        private boolean durableDbCursorOutputEnabled() {
            return isDbSourceMode()
                && outputModes(outputMode).contains("db")
                && dbCursorName != null
                && !dbCursorName.isBlank();
        }

        private boolean isDbSourceMode() {
            return switch (sourceMode.trim().toLowerCase(Locale.ROOT)) {
                case "db", "postgres", "postgresql", "timescale", "timescaledb" -> true;
                default -> false;
            };
        }

        private static List<String> outputModes(String raw) {
            List<String> modes = new ArrayList<>();
            for (String mode : csv(raw)) {
                String normalized = mode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
                if ("both".equals(normalized)) {
                    modes.add("stdout");
                    modes.add("db");
                } else if ("stdout".equals(normalized) || "console".equals(normalized)) {
                    modes.add("stdout");
                } else if (
                    "db".equals(normalized)
                        || "postgres".equals(normalized)
                        || "postgresql".equals(normalized)
                        || "timescale".equals(normalized)
                        || "timescaledb".equals(normalized)
                ) {
                    modes.add("db");
                } else {
                    modes.add(normalized);
                }
            }
            if (modes.isEmpty()) {
                throw new IllegalArgumentException("FEATUREPLANT_OUTPUT must include at least one output.");
            }
            return modes.stream().distinct().toList();
        }

        private static List<StreamContract> resolveStreams(String raw) {
            List<StreamContract> resolved = new ArrayList<>();
            for (String streamName : csv(raw)) {
                Optional<StreamContract> stream = StreamRegistry.byName(streamName);
                if (stream.isEmpty()) {
                    throw new IllegalArgumentException("Unknown featureplant stream: " + streamName);
                }
                resolved.add(stream.get());
            }
            if (resolved.isEmpty()) {
                throw new IllegalArgumentException("FEATUREPLANT_STREAMS must include at least one stream.");
            }
            return List.copyOf(resolved);
        }

        private static List<FeatureModule> resolveModules(String raw) {
            List<FeatureModule> modules = new ArrayList<>();
            for (String module : csv(raw)) {
                switch (module.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                    case "bbo", "best_bid_offer" -> modules.add(new BestBidOfferFeatureModule());
                    case "ticker", "ticker_snapshot" -> modules.add(new TickerSnapshotFeatureModule());
                    case "trade", "trade_tape" -> modules.add(new TradeTapeFeatureModule());
                    default -> throw new IllegalArgumentException("Unknown feature module: " + module);
                }
            }
            if (modules.isEmpty()) {
                throw new IllegalArgumentException("FEATUREPLANT_MODULES must include at least one module.");
            }
            return List.copyOf(modules);
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

        private static int intValue(Map<String, String> env, String key, int defaultValue) {
            return parseInt(value(env, key, Integer.toString(defaultValue)), key);
        }

        private static long longValue(Map<String, String> env, String key, long defaultValue) {
            return parseLong(value(env, key, Long.toString(defaultValue)), key);
        }

        private static int positiveIntValue(Map<String, String> env, String key, String defaultValue) {
            return parsePositiveInt(value(env, key, defaultValue), key);
        }

        private static int nonNegativeIntValue(Map<String, String> env, String key, int defaultValue) {
            return parseNonNegativeInt(value(env, key, Integer.toString(defaultValue)), key);
        }

        private static long nonNegativeLongValue(Map<String, String> env, String key, long defaultValue) {
            return parseNonNegativeLong(value(env, key, Long.toString(defaultValue)), key);
        }

        private static boolean booleanValue(Map<String, String> env, String key, boolean defaultValue) {
            String raw = value(env, key, Boolean.toString(defaultValue)).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(raw)) {
                return true;
            }
            if ("false".equals(raw)) {
                return false;
            }
            throw new IllegalArgumentException(key + " must be true or false.");
        }

        private static int parsePositiveInt(String value, String key) {
            int parsed = parseInt(value, key);
            if (parsed <= 0) {
                throw new IllegalArgumentException(key + " must be positive.");
            }
            return parsed;
        }

        private static int parseNonNegativeInt(String value, String key) {
            int parsed = parseInt(value, key);
            if (parsed < 0) {
                throw new IllegalArgumentException(key + " must be non-negative.");
            }
            return parsed;
        }

        private static long parseNonNegativeLong(String value, String key) {
            long parsed = parseLong(value, key);
            if (parsed < 0L) {
                throw new IllegalArgumentException(key + " must be non-negative.");
            }
            return parsed;
        }

        private static int parseInt(String value, String key) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be an integer.", e);
            }
        }

        private static long parseLong(String value, String key) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be an integer.", e);
            }
        }
    }

    @FunctionalInterface
    interface MetricsServerHandle extends AutoCloseable {
        @Override
        void close();
    }

    @FunctionalInterface
    interface MetricsServerFactory {
        MetricsServerHandle start(String host, int port, BackendMetrics metrics);
    }

    @FunctionalInterface
    interface DbOutputSinkFactory {
        FeatureOutputSink create(
            String dbUrl,
            String dbUser,
            String dbPassword,
            boolean asyncEnabled,
            int queueCapacity,
            int batchSize,
            long closeTimeoutMs,
            BackendMetrics metrics
        );
    }

    @FunctionalInterface
    interface DbProjectorFactory {
        FeaturePlantDbProjector create(
            String dbUrl,
            String dbUser,
            String dbPassword,
            List<StreamContract> streams,
            List<FeatureModule> modules,
            long maxEvents,
            boolean includeReplayEvents,
            String replayId,
            String cursorName,
            BackendMetrics metrics
        );
    }

    private static void printUsageAndExit() {
        System.out.println("""
            Usage: FeaturePlantCli [options]

            Options:
              --source=db|recording|aeron
              --root=/path/to/recordings
              --channel=aeron:udp?endpoint=224.0.1.1:40456
              --db-url=jdbc:postgresql://db:5432/kalshi
              --db-user=kalshi
              --db-password=secret
              --include-replay
              --replay-id=replay-20260519
              --db-cursor-name=featureplant-prod
              --streams=canonical.trade,canonical.ticker,derived.top_of_book
              --modules=bbo,ticker_snapshot,trade_tape
              --max-events=100000
              --batch-size=100
              --metrics-host=0.0.0.0
              --metrics-port=8094
              --output=stdout|db|stdout,db
              --db-output-async
              --db-output-sync
              --db-output-queue-capacity=250000
              --db-output-batch-size=500
              --db-output-close-timeout-ms=5000
              --run-once
              --follow
            """);
        System.exit(0);
    }
}
