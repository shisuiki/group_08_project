package edu.illinois.group8.replay;

import edu.illinois.group8.parser.KalshiCanonicalParser;
import edu.illinois.group8.persistence.FileEventJournal;
import edu.illinois.group8.persistence.RawJournalReader;
import edu.illinois.group8.publication.CollectingEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void rawJournalReplaysToSameCanonicalSequence() {
        KalshiCanonicalParser parser = new KalshiCanonicalParser();
        FileEventJournal journal = new FileEventJournal(tempDir);
        journal.appendRaw(parser.parseWebSocketMessage("""
            {"type":"trade","sid":11,"msg":{"trade_id":"abc","market_ticker":"M","yes_price_dollars":"0.360","no_price_dollars":"0.640","count_fp":"1.00","taker_side":"yes","ts_ms":1669149841000}}
            """).rawSourceEvent());

        ReplayService service = new ReplayService(parser, new RawJournalReader(tempDir));
        CollectingEventPublisher publisher = new CollectingEventPublisher();
        ReplaySummary summary = service.replay(
            new ReplayOptions(tempDir, List.of("M"), null, null, ReplayMode.MULTIPLIER, 1000.0, "test-replay"),
            publisher,
            new ReplayController()
        );

        assertEquals(1L, summary.rawEventsRead());
        assertEquals(1L, summary.canonicalEventsPublished());
        assertEquals("market_trade", publisher.events().get(0).eventType());
        assertEquals("test-replay", publisher.events().get(0).metadata().replayId());
        assertTrue(publisher.events().get(0).eventId().contains("trade"));
    }
}
