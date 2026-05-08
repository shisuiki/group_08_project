package edu.illinois.group8.recorder;

import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.time.TimestampSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record StreamRecorderConfig(
    String host,
    int port,
    Path outputRoot,
    String aeronChannel,
    List<StreamContract> streams,
    TimestampSource timestampSource,
    int recentEventLimit,
    StreamRecordingWriter.PartitionGranularity partitionGranularity,
    int idleSleepMillis
) {
    public static StreamRecorderConfig fromEnvironment() {
        Map<String, String> env = System.getenv();
        String baseDir = value(env, "BASE_DIR", "/app");
        return new StreamRecorderConfig(
            value(env, "STREAM_RECORDER_HOST", "0.0.0.0"),
            intValue(env, "STREAM_RECORDER_PORT", 8092),
            Path.of(value(env, "STREAM_RECORDER_OUTPUT_ROOT", baseDir + "/recordings")),
            value(env, "STREAM_RECORDER_AERON_CHANNEL",
                value(env, "AERON_EXTERNAL_CHANNEL", "aeron:udp?endpoint=224.0.1.1:40456")),
            resolveStreams(value(env, "STREAM_RECORDER_STREAMS",
                "canonical.trade,canonical.ticker,canonical.open_interest,canonical.orderbook.snapshot,canonical.orderbook.delta,canonical.market_lifecycle,system.parser_errors,derived.top_of_book,system.sequence_gaps")),
            TimestampSource.fromEnvironment(),
            intValue(env, "STREAM_RECORDER_RECENT_EVENTS", 200),
            StreamRecordingWriter.PartitionGranularity.from(value(env, "STREAM_RECORDER_PARTITION_GRANULARITY", "hour")),
            nonNegativeIntValue(env, "STREAM_RECORDER_IDLE_SLEEP_MS", 1)
        );
    }

    private static List<StreamContract> resolveStreams(String raw) {
        List<StreamContract> streams = new ArrayList<>();
        for (String streamName : csv(raw)) {
            Optional<StreamContract> contract = StreamRegistry.byName(streamName);
            if (contract.isEmpty()) {
                throw new IllegalArgumentException("Unknown recorder stream: " + streamName);
            }
            streams.add(contract.get());
        }
        if (streams.isEmpty()) {
            throw new IllegalArgumentException("STREAM_RECORDER_STREAMS must include at least one normalized stream.");
        }
        return List.copyOf(streams);
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
        String raw = value(env, key, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer: " + raw, e);
        }
    }

    private static int nonNegativeIntValue(Map<String, String> env, String key, int defaultValue) {
        int value = intValue(env, key, defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be non-negative: " + value);
        }
        return value;
    }
}
