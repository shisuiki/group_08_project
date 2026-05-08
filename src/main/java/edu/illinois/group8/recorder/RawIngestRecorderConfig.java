package edu.illinois.group8.recorder;

import edu.illinois.group8.time.TimestampSource;

import java.nio.file.Path;
import java.util.Map;

public record RawIngestRecorderConfig(
    boolean enabled,
    Path outputRoot,
    String source,
    String captureId,
    int queueCapacity,
    boolean dropOnFull,
    TimestampSource timestampSource,
    StreamRecordingWriter.PartitionGranularity partitionGranularity
) {
    public static RawIngestRecorderConfig fromEnvironment() {
        Map<String, String> env = System.getenv();
        String baseDir = value(env, "BASE_DIR", "/app");
        return new RawIngestRecorderConfig(
            booleanValue(env, "RAW_INGEST_RECORDER_ENABLED", false),
            Path.of(value(env, "RAW_INGEST_RECORDER_ROOT", baseDir + "/recordings/raw-ingest")),
            value(env, "RAW_INGEST_RECORDER_SOURCE", "kalshi.websocket"),
            value(env, "RAW_INGEST_RECORDER_CAPTURE_ID", "live"),
            positiveIntValue(env, "RAW_INGEST_RECORDER_QUEUE_CAPACITY", 250_000),
            booleanValue(env, "RAW_INGEST_RECORDER_DROP_ON_FULL", true),
            TimestampSource.fromEnvironment(),
            StreamRecordingWriter.PartitionGranularity.from(value(env, "RAW_INGEST_RECORDER_PARTITION_GRANULARITY", "minute"))
        );
    }

    private static String value(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static boolean booleanValue(Map<String, String> env, String key, boolean defaultValue) {
        String raw = value(env, key, Boolean.toString(defaultValue));
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static int positiveIntValue(Map<String, String> env, String key, int defaultValue) {
        String raw = value(env, key, Integer.toString(defaultValue));
        try {
            int value = Integer.parseInt(raw);
            if (value < 1) {
                throw new IllegalArgumentException(key + " must be positive: " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer: " + raw, e);
        }
    }
}
