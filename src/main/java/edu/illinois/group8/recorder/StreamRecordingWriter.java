package edu.illinois.group8.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.time.TimestampSource;

import java.io.IOException;
import java.nio.file.Path;

public class StreamRecordingWriter {
    public enum PartitionGranularity {
        HOUR,
        MINUTE;

        public static PartitionGranularity from(String value) {
            if (value == null || value.isBlank()) {
                return HOUR;
            }
            return switch (value.trim().toLowerCase()) {
                case "hour", "hourly" -> HOUR;
                case "minute", "minutely" -> MINUTE;
                default -> throw new IllegalArgumentException("Unsupported recorder partition granularity: " + value);
            };
        }
    }

    private final CanonicalRecordingWriter writer;

    public StreamRecordingWriter(Path outputRoot, TimestampSource timestampSource, BackendMetrics metrics) {
        this(outputRoot, timestampSource, metrics, PartitionGranularity.HOUR);
    }

    public StreamRecordingWriter(
        Path outputRoot,
        TimestampSource timestampSource,
        BackendMetrics metrics,
        PartitionGranularity partitionGranularity
    ) {
        this.writer = new CanonicalRecordingWriter(
            outputRoot,
            "canonical",
            timestampSource,
            metrics,
            partitionGranularity,
            "stream_recorder",
            "recorder_metadata",
            "consumer_receive_ts_ns"
        );
    }

    public synchronized JsonNode write(String streamName, String payload, long consumerReceiveTsNs) throws IOException {
        return writer.write(streamName, payload, consumerReceiveTsNs);
    }
}
