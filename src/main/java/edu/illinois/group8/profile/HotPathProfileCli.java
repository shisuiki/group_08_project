package edu.illinois.group8.profile;

import edu.illinois.group8.book.OrderBookStateManager;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.esb.DataProcessor;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.CanonicalParseResult;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import edu.illinois.group8.persistence.EventJournal;
import edu.illinois.group8.persistence.FileEventJournal;
import edu.illinois.group8.persistence.NoopEventJournal;
import edu.illinois.group8.publication.EventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class HotPathProfileCli {
    private HotPathProfileCli() {
    }

    public static void main(String[] args) throws Exception {
        ProfileConfig config = ProfileConfig.parse(args);
        String[] messages = buildMessages(config);
        ProfileRunner runner = new ProfileRunner(config, messages);
        ProfileResult result = runner.run();
        System.out.println(result.format());
        if (config.printMetrics) {
            System.out.println();
            System.out.println("# Prometheus metrics");
            System.out.print(result.metrics.prometheusText());
        }
    }

    private static String[] buildMessages(ProfileConfig config) {
        int total = config.warmupIterations + config.iterations;
        SyntheticKalshiMessageGenerator generator = new SyntheticKalshiMessageGenerator(
            config.marketCount,
            config.startTimestampMs
        );
        String[] messages = new String[total];
        for (int i = 0; i < total; i++) {
            messages[i] = generator.next(i);
        }
        return messages;
    }

    private static final class ProfileRunner {
        private final ProfileConfig config;
        private final String[] messages;
        private final KalshiCanonicalParser parser = new KalshiCanonicalParser();
        private final BackendMetrics metrics = new BackendMetrics();

        private long canonicalEvents;
        private long generatedEvents;
        private long publishedEvents;
        private long publishedBytes;

        private ProfileRunner(ProfileConfig config, String[] messages) {
            this.config = config;
            this.messages = messages;
        }

        private ProfileResult run() throws Exception {
            Operation operation = operation();
            for (int i = 0; i < config.warmupIterations; i++) {
                operation.process(messages[i]);
            }

            LatencyStats latencies = new LatencyStats(config.iterations);
            long measuredStart = System.nanoTime();
            int offset = config.warmupIterations;
            for (int i = 0; i < config.iterations; i++) {
                long start = System.nanoTime();
                operation.process(messages[offset + i]);
                latencies.record(System.nanoTime() - start);
            }
            long measuredEnd = System.nanoTime();
            operation.close();
            return new ProfileResult(
                config,
                latencies.summarize(),
                measuredEnd - measuredStart,
                canonicalEvents,
                generatedEvents,
                publishedEvents,
                publishedBytes,
                metrics
            );
        }

        private Operation operation() throws Exception {
            return switch (config.mode) {
                case PARSE_ONLY -> message -> {
                    CanonicalParseResult result = parser.parseWebSocketMessage(message);
                    canonicalEvents += result.canonicalEvents().size();
                };
                case PARSE_BOOK -> {
                    OrderBookStateManager bookState = new OrderBookStateManager();
                    yield message -> {
                        CanonicalParseResult result = parser.parseWebSocketMessage(message);
                        canonicalEvents += result.canonicalEvents().size();
                        for (CanonicalEvent event : result.canonicalEvents()) {
                            int generated = bookState.apply(event).generatedEvents().size();
                            generatedEvents += generated;
                        }
                    };
                }
                case PROCESSOR_NOOP -> processorOperation(new NoopEventJournal(), new ProfilingEventPublishers.BlackholePublisher());
                case PROCESSOR_SERIALIZE -> processorOperation(new NoopEventJournal(), new ProfilingEventPublishers.SerializingPublisher());
                case PROCESSOR_FILE_JOURNAL -> {
                    Path root = config.journalRoot == null ? Files.createTempDirectory("kalshi-hotpath-profile-") : config.journalRoot;
                    yield processorOperation(new FileEventJournal(root, new edu.illinois.group8.canonical.JsonCanonicalSerializer(), metrics),
                        new ProfilingEventPublishers.BlackholePublisher());
                }
                case PROCESSOR_FULL_LOCAL -> {
                    Path root = config.journalRoot == null ? Files.createTempDirectory("kalshi-hotpath-profile-") : config.journalRoot;
                    yield processorOperation(new FileEventJournal(root, new edu.illinois.group8.canonical.JsonCanonicalSerializer(), metrics),
                        new ProfilingEventPublishers.SerializingPublisher());
                }
            };
        }

        private Operation processorOperation(EventJournal journal, EventPublisher publisher) {
            ProfilingEventPublishers.CountingPublisher countingPublisher = new ProfilingEventPublishers.CountingPublisher(publisher);
            DataProcessor processor = new DataProcessor(
                parser,
                new OrderBookStateManager(),
                countingPublisher,
                journal,
                metrics
            );
            return new Operation() {
                @Override
                public void process(String message) {
                    processor.processMessage(message);
                }

                @Override
                public void close() {
                    publishedEvents = countingPublisher.events();
                    EventPublisher delegate = countingPublisher.delegate();
                    if (delegate instanceof ProfilingEventPublishers.SerializingPublisher serializingPublisher) {
                        publishedBytes = serializingPublisher.bytes();
                    }
                }
            };
        }
    }

    private interface Operation {
        void process(String message) throws Exception;

        default void close() throws Exception {
        }
    }

    private record ProfileResult(
        ProfileConfig config,
        LatencyStats.Summary latency,
        long elapsedNs,
        long canonicalEvents,
        long generatedEvents,
        long publishedEvents,
        long publishedBytes,
        BackendMetrics metrics
    ) {
        private String format() {
            double seconds = elapsedNs / 1_000_000_000.0;
            double messagesPerSecond = config.iterations / seconds;
            return """
                mode=%s
                warmup_messages=%d
                measured_messages=%d
                markets=%d
                elapsed_ms=%.3f
                throughput_msg_per_sec=%.2f
                canonical_events=%d
                generated_events=%d
                published_events=%d
                serialized_bytes=%d
                latency_ns_min=%d
                latency_ns_mean=%d
                latency_ns_p50=%d
                latency_ns_p90=%d
                latency_ns_p95=%d
                latency_ns_p99=%d
                latency_ns_max=%d
                """.formatted(
                config.mode.name().toLowerCase(Locale.ROOT),
                config.warmupIterations,
                config.iterations,
                config.marketCount,
                elapsedNs / 1_000_000.0,
                messagesPerSecond,
                canonicalEvents,
                generatedEvents,
                publishedEvents,
                publishedBytes,
                latency.minNs(),
                latency.meanNs(),
                latency.p50Ns(),
                latency.p90Ns(),
                latency.p95Ns(),
                latency.p99Ns(),
                latency.maxNs()
            );
        }
    }

    private enum ProfileMode {
        PARSE_ONLY,
        PARSE_BOOK,
        PROCESSOR_NOOP,
        PROCESSOR_SERIALIZE,
        PROCESSOR_FILE_JOURNAL,
        PROCESSOR_FULL_LOCAL
    }

    private static final class ProfileConfig {
        private ProfileMode mode = ProfileMode.PARSE_ONLY;
        private int iterations = 100_000;
        private int warmupIterations = 20_000;
        private int marketCount = 1;
        private long startTimestampMs = 1_700_000_000_000L;
        private Path journalRoot;
        private boolean printMetrics;

        private static ProfileConfig parse(String[] args) {
            ProfileConfig config = new ProfileConfig();
            for (String arg : args) {
                if (arg.equals("--help") || arg.equals("-h")) {
                    printUsageAndExit();
                } else if (arg.startsWith("--mode=")) {
                    config.mode = ProfileMode.valueOf(arg.substring("--mode=".length()).trim().toUpperCase(Locale.ROOT).replace('-', '_'));
                } else if (arg.startsWith("--iterations=")) {
                    config.iterations = Integer.parseInt(arg.substring("--iterations=".length()));
                } else if (arg.startsWith("--warmup=")) {
                    config.warmupIterations = Integer.parseInt(arg.substring("--warmup=".length()));
                } else if (arg.startsWith("--markets=")) {
                    config.marketCount = Integer.parseInt(arg.substring("--markets=".length()));
                } else if (arg.startsWith("--journal-root=")) {
                    config.journalRoot = Path.of(arg.substring("--journal-root=".length()));
                } else if (arg.startsWith("--start-ts-ms=")) {
                    config.startTimestampMs = Long.parseLong(arg.substring("--start-ts-ms=".length()));
                } else if (arg.equals("--print-metrics")) {
                    config.printMetrics = true;
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            if (config.iterations <= 0 || config.warmupIterations < 0 || config.marketCount <= 0) {
                throw new IllegalArgumentException("iterations and markets must be positive; warmup must be non-negative");
            }
            return config;
        }

        private static void printUsageAndExit() {
            System.out.println("""
                Usage: HotPathProfileCli [options]

                Options:
                  --mode=parse-only|parse-book|processor-noop|processor-serialize|processor-file-journal|processor-full-local
                  --iterations=N
                  --warmup=N
                  --markets=N
                  --journal-root=/path
                  --start-ts-ms=N
                  --print-metrics

                Run with JFR:
                  java -XX:StartFlightRecording=filename=hotpath.jfr,settings=profile,dumponexit=true \\
                    -cp target/kalshi-project-1.0-SNAPSHOT.jar \\
                    edu.illinois.group8.profile.HotPathProfileCli --mode=parse-book
                """);
            System.exit(0);
        }
    }
}
