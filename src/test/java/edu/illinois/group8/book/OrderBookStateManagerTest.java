package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.TopOfBookUpdate;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OrderBookStateManagerTest {
    private final KalshiCanonicalParser parser = new KalshiCanonicalParser();

    @Test
    void interleavedSubscriptionSequencesDoNotPausePerMarketOrderBook() {
        OrderBookStateManager manager = new OrderBookStateManager();

        manager.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M1","yes_dollars_fp":[["0.4500","10.00"]],"no_dollars_fp":[["0.4000","7.00"]]}}
            """));
        manager.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":3,"msg":{"market_ticker":"M2","yes_dollars_fp":[["0.4400","8.00"]],"no_dollars_fp":[["0.4100","6.00"]]}}
            """));

        BookUpdateResult result = manager.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":4,"msg":{"market_ticker":"M1","price_dollars":"0.4600","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """));

        assertEquals(1, result.generatedEvents().size());
        TopOfBookUpdate top = assertInstanceOf(TopOfBookUpdate.class, result.generatedEvents().get(0));
        assertEquals(460_000L, top.bidPriceMicros());
        assertEquals(600_000L, top.askPriceMicros());
        assertFalse(top.crossed());
        OrderBookState m1 = manager.getState("M1");
        assertFalse(m1.pausedForRecovery());
        assertEquals(Long.valueOf(4L), m1.lastSequence());
        assertEquals(460_000L, m1.currentTopOfBook().bidPriceMicros());
    }

    private CanonicalEvent event(String json) {
        return parser.parseWebSocketMessage(json).canonicalEvents().get(0);
    }
}
