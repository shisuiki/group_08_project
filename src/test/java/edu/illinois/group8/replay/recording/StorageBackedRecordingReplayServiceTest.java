package edu.illinois.group8.replay.recording;

import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.metrics.BackendMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBackedRecordingReplayServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void replaysRecordedEventsAcrossStreamsAndLoops() throws Exception {
        write("canonical.trade", "t1", "market_trade", 1000L);
        write("canonical.ticker", "q1", "ticker_update", 1001L);
        write("derived.top_of_book", "b1", "top_of_book_update", 1002L);

        RecordingReplayConfig config = new RecordingReplayConfig(
            tempDir,
            "aeron:udp?endpoint=224.0.1.1:40456",
            List.of(
                StreamRegistry.byName("canonical.trade").orElseThrow(),
                StreamRegistry.byName("canonical.ticker").orElseThrow(),
                StreamRegistry.byName("derived.top_of_book").orElseThrow()
            ),
            RecordingReplayMode.AS_FAST_AS_POSSIBLE,
            1.0,
            0L,
            0L,
            3,
            "load-test",
            false,
            false
        );
        BackendMetrics metrics = new BackendMetrics();
        CollectingRecordingReplayPublisher publisher = new CollectingRecordingReplayPublisher();
        StorageBackedRecordingReplayService service = new StorageBackedRecordingReplayService(
            new RecordingEventReader(tempDir),
            metrics
        );

        RecordingReplaySummary summary = service.replay(config, publisher);

        assertEquals(3L, summary.sourceEventsLoaded());
        assertEquals(9L, summary.eventsAttempted());
        assertEquals(9L, summary.eventsPublished());
        assertEquals(3L, summary.eventsByStream().get("canonical.trade"));
        assertEquals(3L, summary.eventsByStream().get("canonical.ticker"));
        assertEquals(3L, summary.eventsByStream().get("derived.top_of_book"));
        assertEquals(9, publisher.events().size());
        assertEquals(3L, metrics.get(
            "backend_replay_events_published_total",
            BackendMetrics.labels("service", "recording_replay", "stream", "canonical.trade", "replay_id", "load-test")
        ));
    }

    @Test
    void canAnnotateReplayedPayloads() throws Exception {
        write("canonical.trade", "t1", "market_trade", 1000L);
        RecordingReplayConfig config = new RecordingReplayConfig(
            tempDir,
            "aeron:udp?endpoint=224.0.1.1:40456",
            List.of(StreamRegistry.byName("canonical.trade").orElseThrow()),
            RecordingReplayMode.AS_FAST_AS_POSSIBLE,
            1.0,
            0L,
            0L,
            1,
            "annotated",
            true,
            false
        );
        CollectingRecordingReplayPublisher publisher = new CollectingRecordingReplayPublisher();

        new StorageBackedRecordingReplayService(new RecordingEventReader(tempDir), new BackendMetrics())
            .replay(config, publisher);

        assertTrue(publisher.payloads().get(0).contains("\"replay_id\":\"annotated\""));
        assertTrue(publisher.payloads().get(0).contains("\"replay_source\":\"stream_recorder_storage\""));
    }

    private void write(String streamName, String eventId, String eventType, long eventTsMs) throws Exception {
        Path file = tempDir
            .resolve("canonical")
            .resolve(streamName.replace('.', '_'))
            .resolve("2023-11-14.ndjson");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {"event_id":"%s","event_type":"%s","schema_version":1,"stream_name":"%s","metadata":{"source":"kalshi","market_ticker":"M","event_ts_ms":%d},"recorder_metadata":{"storage_commit_ts_ns":%d}}
            """.formatted(eventId, eventType, streamName, eventTsMs, eventTsMs).trim() + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
    }
}
