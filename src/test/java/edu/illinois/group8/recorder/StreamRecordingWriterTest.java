package edu.illinois.group8.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.time.TimestampSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamRecordingWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesCanonicalStreamEventWithRecorderMetadata() throws Exception {
        BackendMetrics metrics = new BackendMetrics();
        StreamRecordingWriter writer = new StreamRecordingWriter(
            tempDir,
            TimestampSource.from("system_nano", "/dev/missing_ptp"),
            metrics
        );

        JsonNode recorded = writer.write(
            "canonical.trade",
            """
                {"event_id":"e1","event_type":"market_trade","schema_version":1,"stream_name":"canonical.trade","metadata":{"source":"kalshi","market_ticker":"M","event_ts_ms":1700000000000},"trade_id":"t1","yes_price_micros":250000,"no_price_micros":750000,"quantity_micros":1000000,"taker_side":"yes"}
                """,
            123L
        );

        assertEquals("e1", recorded.path("event_id").asText());
        assertEquals(123L, recorded.path("recorder_metadata").path("consumer_receive_ts_ns").asLong());
        assertTrue(recorded.path("recorder_metadata").path("storage_commit_ts_ns").isNumber());

        List<Path> files;
        try (Stream<Path> paths = Files.walk(tempDir.resolve("canonical"))) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        assertEquals(1, files.size());
        assertTrue(files.get(0).toString().contains("stream=canonical.trade"));
        assertTrue(files.get(0).toString().contains("date=2023-11-14"));
        assertTrue(files.get(0).toString().contains("hour=22"));
        assertEquals("events.ndjson", files.get(0).getFileName().toString());

        JsonNode persisted = new JsonCanonicalSerializer().mapper().readTree(Files.readString(files.get(0)).trim());
        assertEquals("e1", persisted.path("event_id").asText());
        assertTrue(persisted.path("recorder_metadata").path("storage_commit_wall_ts").isTextual());
        assertEquals(1L, metrics.get(
            "backend_storage_commit_total",
            BackendMetrics.labels("service", "stream_recorder", "stream", "canonical.trade")
        ));
        assertTrue(metrics.prometheusText().contains("backend_storage_commit_latency_ns_count{service=\"stream_recorder\",stream=\"canonical.trade\"} 1"));
    }

    @Test
    void canPartitionRecorderFilesByMinute() throws Exception {
        BackendMetrics metrics = new BackendMetrics();
        StreamRecordingWriter writer = new StreamRecordingWriter(
            tempDir,
            TimestampSource.from("system_nano", "/dev/missing_ptp"),
            metrics,
            StreamRecordingWriter.PartitionGranularity.MINUTE
        );

        writer.write(
            "canonical.trade",
            """
                {"event_id":"e2","event_type":"market_trade","schema_version":1,"stream_name":"canonical.trade","metadata":{"source":"kalshi","market_ticker":"M","event_ts_ms":1700000123456},"trade_id":"t2","yes_price_micros":250000,"no_price_micros":750000,"quantity_micros":1000000,"taker_side":"yes"}
                """,
            456L
        );

        List<Path> files;
        try (Stream<Path> paths = Files.walk(tempDir.resolve("canonical"))) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        assertEquals(1, files.size());
        assertTrue(files.get(0).toString().contains("hour=22"));
        assertTrue(files.get(0).toString().contains("minute=15"));
    }
}
