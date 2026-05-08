package edu.illinois.group8.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageBackedRecordingConsumerTest {
    @TempDir
    Path tempDir;

    @Test
    void readsInitialRecordingsAndOnlyTailsAppendedLines() throws Exception {
        Path file = tempDir.resolve("canonical").resolve("canonical_trade").resolve("2026-05-02.ndjson");
        Files.createDirectories(file.getParent());
        Files.writeString(file, event("e1", 1000L), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        GatewayEventStore store = new GatewayEventStore(100);
        StorageBackedRecordingConsumer consumer = new StorageBackedRecordingConsumer(
            tempDir,
            store,
            new GatewayWebSocketBroadcaster(),
            1000L
        );

        assertEquals(1L, consumer.loadInitial());
        assertEquals(0L, consumer.pollOnce());

        Files.writeString(file, event("e2", 2000L), StandardOpenOption.APPEND);
        assertEquals(1L, consumer.pollOnce());

        assertEquals(2L, store.symbols("").get(0).get("event_count"));
        assertEquals(2L, consumer.stats().get("events_read"));
    }

    private static String event(String id, long tsMs) {
        return """
            {"event_id":"%s","event_type":"market_trade","schema_version":1,"stream_name":"canonical.trade","metadata":{"market_ticker":"M","market_id":"m1","event_ts_ms":%d},"trade_id":"%s","yes_price_micros":250000,"no_price_micros":750000,"quantity_micros":1000000,"taker_side":"yes"}
            """.formatted(id, tsMs, id);
    }
}
