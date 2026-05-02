package edu.illinois.group8.persistence;

import edu.illinois.group8.canonical.MarketTrade;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileEventJournalTest {
    @TempDir
    Path tempDir;

    @Test
    void appendIsIdempotentByEventId() throws Exception {
        KalshiCanonicalParser parser = new KalshiCanonicalParser();
        var result = parser.parseWebSocketMessage("""
            {"type":"trade","sid":11,"msg":{"trade_id":"abc","market_ticker":"M","yes_price_dollars":"0.360","no_price_dollars":"0.640","count_fp":"1.00","taker_side":"yes","ts_ms":1669149841000}}
            """);
        MarketTrade trade = (MarketTrade) result.canonicalEvents().get(0);
        FileEventJournal journal = new FileEventJournal(tempDir);

        journal.appendCanonical(trade);
        journal.appendCanonical(trade);

        long lines;
        try (var stream = Files.walk(tempDir)) {
            Path file = stream.filter(path -> path.toString().endsWith(".ndjson")).findFirst().orElseThrow();
            lines = Files.lines(file).count();
        }
        assertEquals(1L, lines);
    }
}
