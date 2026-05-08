package edu.illinois.group8.feature;

import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class FeaturePlantCli {
    private FeaturePlantCli() {
    }

    public static void main(String[] args) {
        Config config = Config.fromEnvironment().withArgs(args);
        CanonicalEnvelopeSource source = config.source();
        FeatureOutputSink sink = new StdoutFeatureOutputSink();
        try (
             FeaturePlantService service = new FeaturePlantService(source, config.modules(), sink)) {
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

    private record Config(
        String sourceMode,
        Path recordingRoot,
        String aeronChannel,
        List<StreamContract> streams,
        List<FeatureModule> modules,
        long maxEvents,
        int batchSize,
        int idleSleepMillis,
        boolean runOnce
    ) {
        static Config fromEnvironment() {
            Map<String, String> env = System.getenv();
            String baseDir = value(env, "BASE_DIR", "/app");
            return new Config(
                value(env, "FEATUREPLANT_SOURCE", "recording"),
                Path.of(value(env, "FEATUREPLANT_RECORDING_ROOT", baseDir + "/recordings")),
                value(env, "FEATUREPLANT_AERON_CHANNEL",
                    value(env, "AERON_EXTERNAL_CHANNEL", "aeron:udp?endpoint=224.0.1.1:40456")),
                resolveStreams(value(env, "FEATUREPLANT_STREAMS",
                    "canonical.trade,canonical.ticker,derived.top_of_book")),
                resolveModules(value(env, "FEATUREPLANT_MODULES", "bbo,ticker_snapshot,trade_tape")),
                longValue(env, "FEATUREPLANT_MAX_EVENTS", 0L),
                intValue(env, "FEATUREPLANT_BATCH_SIZE", 100),
                intValue(env, "FEATUREPLANT_IDLE_SLEEP_MS", 1),
                Boolean.parseBoolean(value(env, "FEATUREPLANT_RUN_ONCE", "true"))
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
                nextRunOnce
            );
        }

        CanonicalEnvelopeSource source() {
            return switch (sourceMode.trim().toLowerCase(Locale.ROOT)) {
                case "recording", "history", "storage" ->
                    RecordingCanonicalEnvelopeSource.fromRoot(recordingRoot, streams, maxEvents);
                case "aeron", "live", "tickerplant" -> new AeronCanonicalEnvelopeSource(aeronChannel, streams);
                default -> throw new IllegalArgumentException("Unsupported FEATUREPLANT_SOURCE: " + sourceMode);
            };
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
            return Integer.parseInt(value(env, key, Integer.toString(defaultValue)));
        }

        private static long longValue(Map<String, String> env, String key, long defaultValue) {
            return Long.parseLong(value(env, key, Long.toString(defaultValue)));
        }
    }

    private static void printUsageAndExit() {
        System.out.println("""
            Usage: FeaturePlantCli [options]

            Options:
              --source=recording|aeron
              --root=/path/to/recordings
              --channel=aeron:udp?endpoint=224.0.1.1:40456
              --streams=canonical.trade,canonical.ticker,derived.top_of_book
              --modules=bbo,ticker_snapshot,trade_tape
              --max-events=100000
              --batch-size=100
              --run-once
              --follow
            """);
        System.exit(0);
    }
}
