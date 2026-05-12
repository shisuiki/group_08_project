package edu.illinois.group8.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.feature.BestBidOfferFeatureModule;
import edu.illinois.group8.feature.FeatureModule;
import edu.illinois.group8.feature.FeaturePlantService;
import edu.illinois.group8.feature.RecordingCanonicalEnvelopeSource;
import edu.illinois.group8.feature.TickerSnapshotFeatureModule;
import edu.illinois.group8.feature.TradeTapeFeatureModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ResearchExportCli {
    private ResearchExportCli() {
    }

    public static void main(String[] args) {
        Config config = Config.parse(args);
        RecordingCanonicalEnvelopeSource source = RecordingCanonicalEnvelopeSource.fromRoot(
            config.recordingRoot, config.streams, config.maxEvents
        );
        long totalEnvelopes;
        Map<String, Long> rowCounts;
        try (CsvFeatureExportSink sink = new CsvFeatureExportSink(
                config.outputDirectory,
                config.markets,
                config.fromTsMs,
                config.toTsMs,
                msg -> System.err.println("research-export: " + msg));
             FeaturePlantService service = new FeaturePlantService(source, config.modules, sink)) {
            totalEnvelopes = service.runUntilExhausted(config.batchSize);
            rowCounts = sink.rowCounts();
        }
        writeMetadata(config, totalEnvelopes, rowCounts);
        System.err.println(
            "research-export processed " + totalEnvelopes + " envelopes; rows=" + rowCounts
        );
    }

    private static void writeMetadata(Config config, long totalEnvelopes, Map<String, Long> rowCounts) {
        ObjectMapper mapper = new JsonCanonicalSerializer().mapper().copy()
            .enable(SerializationFeature.INDENT_OUTPUT);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("run_ts_iso", Instant.now().toString());
        metadata.put("source_mode", config.sourceMode);
        metadata.put("source_root", config.recordingRoot.toString());
        metadata.put("streams", config.streams.stream().map(StreamContract::streamName).toList());
        metadata.put("modules", config.modules.stream().map(FeatureModule::name).toList());
        metadata.put("markets", config.markets.isEmpty() ? null : List.copyOf(config.markets));
        metadata.put("from_ts_ms", config.fromTsMs);
        metadata.put("to_ts_ms", config.toTsMs);
        metadata.put("max_events", config.maxEvents);
        metadata.put("total_envelopes", totalEnvelopes);
        metadata.put("output_rows", rowCounts);
        Path target = config.outputDirectory.resolve("metadata.json");
        try {
            Files.writeString(target, mapper.writeValueAsString(metadata));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }

    record Config(
        String sourceMode,
        Path recordingRoot,
        List<StreamContract> streams,
        List<FeatureModule> modules,
        Path outputDirectory,
        long maxEvents,
        int batchSize,
        Long fromTsMs,
        Long toTsMs,
        Set<String> markets
    ) {
        static Config parse(String[] args) {
            String sourceMode = "recording";
            Path recordingRoot = Path.of("recordings");
            List<StreamContract> streams = List.of();
            List<FeatureModule> modules = List.of();
            Path outputDirectory = null;
            long maxEvents = 0L;
            int batchSize = 100;
            Long fromTsMs = null;
            Long toTsMs = null;
            Set<String> markets = Set.of();

            for (String arg : args) {
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsageAndExit();
                } else if (arg.startsWith("--source=")) {
                    sourceMode = arg.substring("--source=".length());
                } else if (arg.startsWith("--root=")) {
                    recordingRoot = Path.of(arg.substring("--root=".length()));
                } else if (arg.startsWith("--streams=")) {
                    streams = resolveStreams(arg.substring("--streams=".length()));
                } else if (arg.startsWith("--modules=")) {
                    modules = resolveModules(arg.substring("--modules=".length()));
                } else if (arg.startsWith("--output=")) {
                    outputDirectory = Path.of(arg.substring("--output=".length()));
                } else if (arg.startsWith("--max-events=")) {
                    maxEvents = Long.parseLong(arg.substring("--max-events=".length()));
                } else if (arg.startsWith("--batch-size=")) {
                    batchSize = Integer.parseInt(arg.substring("--batch-size=".length()));
                } else if (arg.startsWith("--from-ts-ms=")) {
                    fromTsMs = Long.parseLong(arg.substring("--from-ts-ms=".length()));
                } else if (arg.startsWith("--to-ts-ms=")) {
                    toTsMs = Long.parseLong(arg.substring("--to-ts-ms=".length()));
                } else if (arg.startsWith("--markets=")) {
                    markets = resolveMarkets(arg.substring("--markets=".length()));
                } else {
                    throw new IllegalArgumentException("Unknown research-export argument: " + arg);
                }
            }
            if (!isRecording(sourceMode)) {
                throw new IllegalArgumentException(
                    "Only --source=recording is supported by research-export (got: " + sourceMode + ")"
                );
            }
            if (streams.isEmpty()) {
                throw new IllegalArgumentException("--streams must include at least one canonical stream");
            }
            if (modules.isEmpty()) {
                throw new IllegalArgumentException("--modules must include at least one feature module");
            }
            if (outputDirectory == null) {
                throw new IllegalArgumentException("--output=<dir> is required");
            }
            return new Config(
                sourceMode,
                recordingRoot,
                streams,
                modules,
                outputDirectory,
                Math.max(0L, maxEvents),
                Math.max(1, batchSize),
                fromTsMs,
                toTsMs,
                markets
            );
        }

        private static boolean isRecording(String mode) {
            return switch (mode.trim().toLowerCase(Locale.ROOT)) {
                case "recording", "history", "storage" -> true;
                default -> false;
            };
        }

        private static List<StreamContract> resolveStreams(String raw) {
            List<StreamContract> resolved = new ArrayList<>();
            for (String streamName : csv(raw)) {
                Optional<StreamContract> stream = StreamRegistry.byName(streamName);
                if (stream.isEmpty()) {
                    throw new IllegalArgumentException("Unknown research-export stream: " + streamName);
                }
                resolved.add(stream.get());
            }
            return List.copyOf(resolved);
        }

        private static List<FeatureModule> resolveModules(String raw) {
            List<FeatureModule> resolved = new ArrayList<>();
            for (String module : csv(raw)) {
                switch (module.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                    case "bbo", "best_bid_offer" -> resolved.add(new BestBidOfferFeatureModule());
                    case "ticker", "ticker_snapshot" -> resolved.add(new TickerSnapshotFeatureModule());
                    case "trade", "trade_tape" -> resolved.add(new TradeTapeFeatureModule());
                    default -> throw new IllegalArgumentException("Unknown feature module: " + module);
                }
            }
            return List.copyOf(resolved);
        }

        private static Set<String> resolveMarkets(String raw) {
            return new LinkedHashSet<>(csv(raw));
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
    }

    private static void printUsageAndExit() {
        System.out.println("""
            Usage: ResearchExportCli [options]

            Options:
              --source=recording
              --root=/path/to/recordings
              --streams=canonical.trade,canonical.ticker,derived.top_of_book
              --modules=bbo,ticker_snapshot,trade_tape
              --output=/path/to/output-dir
              --max-events=100000
              --batch-size=200
              --from-ts-ms=1709251200000
              --to-ts-ms=1709337600000
              --markets=KXHIGHCHI-26MAY12-T70,KXHIGHCHI-26MAY12-T75
            """);
        System.exit(0);
    }
}
