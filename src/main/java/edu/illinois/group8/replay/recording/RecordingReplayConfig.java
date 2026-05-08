package edu.illinois.group8.replay.recording;

import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public record RecordingReplayConfig(
    Path recordingRoot,
    String aeronChannel,
    List<StreamContract> streams,
    RecordingReplayMode mode,
    double speedMultiplier,
    long fixedRatePerSecond,
    long maxEvents,
    int loopCount,
    String replayId,
    boolean annotateReplay,
    boolean dryRun
) {
    public RecordingReplayConfig {
        streams = List.copyOf(streams == null ? List.of() : streams);
        mode = mode == null ? RecordingReplayMode.AS_FAST_AS_POSSIBLE : mode;
        speedMultiplier = speedMultiplier <= 0 ? 1.0 : speedMultiplier;
        fixedRatePerSecond = Math.max(0L, fixedRatePerSecond);
        maxEvents = Math.max(0L, maxEvents);
        loopCount = Math.max(1, loopCount);
        replayId = replayId == null || replayId.isBlank() ? "recording-replay-" + UUID.randomUUID() : replayId;
    }

    public static RecordingReplayConfig fromEnvironment() {
        Map<String, String> env = System.getenv();
        String baseDir = value(env, "BASE_DIR", "/app");
        return new RecordingReplayConfig(
            Path.of(value(env, "RECORDING_REPLAY_ROOT", value(env, "STREAM_RECORDER_OUTPUT_ROOT", baseDir + "/recordings"))),
            value(env, "RECORDING_REPLAY_AERON_CHANNEL",
                value(env, "AERON_EXTERNAL_CHANNEL", "aeron:udp?endpoint=224.0.1.1:40456")),
            resolveStreams(value(env, "RECORDING_REPLAY_STREAMS",
                "canonical.trade,canonical.ticker,canonical.open_interest,canonical.orderbook.snapshot,canonical.orderbook.delta,derived.top_of_book,system.sequence_gaps")),
            mode(value(env, "RECORDING_REPLAY_MODE", "as_fast_as_possible")),
            doubleValue(env, "RECORDING_REPLAY_SPEED_MULTIPLIER", 1.0),
            longValue(env, "RECORDING_REPLAY_FIXED_RATE_PER_SECOND", 0L),
            longValue(env, "RECORDING_REPLAY_MAX_EVENTS", 0L),
            intValue(env, "RECORDING_REPLAY_LOOP_COUNT", 1),
            value(env, "RECORDING_REPLAY_ID", ""),
            Boolean.parseBoolean(value(env, "RECORDING_REPLAY_ANNOTATE", "false")),
            Boolean.parseBoolean(value(env, "RECORDING_REPLAY_DRY_RUN", "false"))
        );
    }

    public RecordingReplayConfig withCliArgs(String[] args) {
        Path root = recordingRoot;
        String channel = aeronChannel;
        List<StreamContract> selectedStreams = streams;
        RecordingReplayMode selectedMode = mode;
        double selectedSpeed = speedMultiplier;
        long selectedFixedRate = fixedRatePerSecond;
        long selectedMaxEvents = maxEvents;
        int selectedLoopCount = loopCount;
        String selectedReplayId = replayId;
        boolean selectedAnnotate = annotateReplay;
        boolean selectedDryRun = dryRun;

        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsageAndExit();
            } else if (arg.startsWith("--root=")) {
                root = Path.of(arg.substring("--root=".length()));
            } else if (arg.startsWith("--channel=")) {
                channel = arg.substring("--channel=".length());
            } else if (arg.startsWith("--streams=")) {
                selectedStreams = resolveStreams(arg.substring("--streams=".length()));
            } else if (arg.startsWith("--mode=")) {
                selectedMode = mode(arg.substring("--mode=".length()));
            } else if (arg.startsWith("--speed=")) {
                selectedSpeed = Double.parseDouble(arg.substring("--speed=".length()));
            } else if (arg.startsWith("--fixed-rate=")) {
                selectedFixedRate = Long.parseLong(arg.substring("--fixed-rate=".length()));
                selectedMode = RecordingReplayMode.FIXED_RATE;
            } else if (arg.startsWith("--max-events=")) {
                selectedMaxEvents = Long.parseLong(arg.substring("--max-events=".length()));
            } else if (arg.startsWith("--loop-count=")) {
                selectedLoopCount = Integer.parseInt(arg.substring("--loop-count=".length()));
            } else if (arg.startsWith("--replay-id=")) {
                selectedReplayId = arg.substring("--replay-id=".length());
            } else if ("--annotate-replay".equals(arg)) {
                selectedAnnotate = true;
            } else if ("--dry-run".equals(arg)) {
                selectedDryRun = true;
            } else {
                throw new IllegalArgumentException("Unknown recording replay argument: " + arg);
            }
        }

        return new RecordingReplayConfig(
            root,
            channel,
            selectedStreams,
            selectedMode,
            selectedSpeed,
            selectedFixedRate,
            selectedMaxEvents,
            selectedLoopCount,
            selectedReplayId,
            selectedAnnotate,
            selectedDryRun
        );
    }

    private static List<StreamContract> resolveStreams(String raw) {
        List<StreamContract> resolved = new ArrayList<>();
        for (String streamName : csv(raw)) {
            Optional<StreamContract> contract = StreamRegistry.byName(streamName);
            if (contract.isEmpty()) {
                throw new IllegalArgumentException("Unknown replay stream: " + streamName);
            }
            resolved.add(contract.get());
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("At least one replay stream is required.");
        }
        return resolved;
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

    private static RecordingReplayMode mode(String raw) {
        return RecordingReplayMode.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
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

    private static double doubleValue(Map<String, String> env, String key, double defaultValue) {
        return Double.parseDouble(value(env, key, Double.toString(defaultValue)));
    }

    private static void printUsageAndExit() {
        System.out.println("""
            Usage: StorageBackedRecordingReplayCli [options]

            Options:
              --root=/path/to/recordings
              --channel=aeron:udp?endpoint=224.0.1.1:40456
              --streams=canonical.trade,canonical.ticker,derived.top_of_book
              --mode=as-fast-as-possible|original-timestamps|fixed-rate
              --speed=10.0
              --fixed-rate=50000
              --max-events=100000
              --loop-count=10
              --replay-id=name
              --annotate-replay
              --dry-run
            """);
        System.exit(0);
    }
}
