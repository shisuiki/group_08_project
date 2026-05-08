package edu.illinois.group8.replay.raw;

import edu.illinois.group8.metrics.BackendMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RawIngressReplayServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void replaysRawPayloadsInReceiveTimestampOrder() throws Exception {
        Path file = tempDir
            .resolve("source=kalshi.websocket")
            .resolve("date=2026-05-04")
            .resolve("hour=12")
            .resolve("minute=00")
            .resolve("events.ndjson");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {"receive_ts_ns":200,"connection_id":"c2","sequence":2,"raw_payload":"{\\"type\\":\\"ticker\\",\\"msg\\":{\\"market_ticker\\":\\"B\\"}}"}
            {"receive_ts_ns":100,"connection_id":"c1","sequence":1,"raw_payload":"{\\"type\\":\\"ticker\\",\\"msg\\":{\\"market_ticker\\":\\"A\\"}}"}
            """, StandardCharsets.UTF_8);

        RawIngressReplayConfig config = new RawIngressReplayConfig(
            "local-ndjson",
            tempDir,
            "",
            "",
            "",
            "raw_ingest_events",
            "raw_payload",
            "receive_ts_ns",
            "connection_id",
            "sequence",
            "raw_event_id",
            "market_ticker",
            RawReplayMode.AS_FAST_AS_POSSIBLE,
            1.0,
            0L,
            0L,
            null,
            null,
            java.util.List.of(),
            java.util.List.of(),
            false,
            false,
            "test-raw-replay"
        );
        CollectingRawIngressReplayPublisher publisher = new CollectingRawIngressReplayPublisher();
        RawIngressReplaySummary summary = new RawIngressReplayService(new LocalNdjsonRawReplaySource(tempDir), new BackendMetrics())
            .replay(config, publisher);

        assertEquals(2L, summary.sourceEventsLoaded());
        assertEquals(2L, summary.eventsPublished());
        assertEquals("{\"type\":\"ticker\",\"msg\":{\"market_ticker\":\"A\"}}", publisher.payloads().get(0));
        assertEquals("{\"type\":\"ticker\",\"msg\":{\"market_ticker\":\"B\"}}", publisher.payloads().get(1));
    }
}
