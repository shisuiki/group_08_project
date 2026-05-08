package edu.illinois.group8.replay.recording;

import edu.illinois.group8.canonical.StreamRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordingEventReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void readsRecorderStorageInEventTimestampOrderAcrossStreams() throws Exception {
        write("canonical.trade", "t2", "market_trade", 2000L, 2L);
        write("canonical.ticker", "q1", "ticker_update", 1000L, 3L);
        write("derived.top_of_book", "b1", "top_of_book_update", 1500L, 1L);

        RecordingEventReader reader = new RecordingEventReader(tempDir);
        List<RecordingEvent> events = reader.read(List.of(
            StreamRegistry.byName("canonical.trade").orElseThrow(),
            StreamRegistry.byName("canonical.ticker").orElseThrow(),
            StreamRegistry.byName("derived.top_of_book").orElseThrow()
        ), 0L);

        assertEquals(List.of("q1", "b1", "t2"), events.stream().map(RecordingEvent::eventId).toList());
        assertEquals("derived.top_of_book", events.get(1).streamName());
    }

    private void write(String streamName, String eventId, String eventType, long eventTsMs, long commitTsNs) throws Exception {
        Path file = tempDir
            .resolve("canonical")
            .resolve(streamName.replace('.', '_'))
            .resolve("2023-11-14.ndjson");
        Files.createDirectories(file.getParent());
        Files.writeString(file, event(streamName, eventId, eventType, eventTsMs, commitTsNs) + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
    }

    private String event(String streamName, String eventId, String eventType, long eventTsMs, long commitTsNs) {
        return """
            {"event_id":"%s","event_type":"%s","schema_version":1,"stream_name":"%s","metadata":{"source":"kalshi","market_ticker":"M","event_ts_ms":%d},"recorder_metadata":{"storage_commit_ts_ns":%d}}
            """.formatted(eventId, eventType, streamName, eventTsMs, commitTsNs).trim();
    }
}
