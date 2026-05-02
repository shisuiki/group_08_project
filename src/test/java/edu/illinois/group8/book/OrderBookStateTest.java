package edu.illinois.group8.book;

import edu.illinois.group8.canonical.OrderBookDeltaEvent;
import edu.illinois.group8.canonical.OrderBookSnapshotEvent;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.canonical.TopOfBookUpdate;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OrderBookStateTest {
    private final KalshiCanonicalParser parser = new KalshiCanonicalParser();

    @Test
    void snapshotPublishesDeterministicTopOfBook() {
        OrderBookSnapshotEvent snapshot = snapshot("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M","yes_dollars_fp":[["0.4500","10.00"]],"no_dollars_fp":[["0.4000","7.00"]]}}
            """);
        BookUpdateResult result = new OrderBookState("M").applySnapshot(snapshot);

        TopOfBookUpdate top = assertInstanceOf(TopOfBookUpdate.class, result.generatedEvents().get(0));
        assertEquals(450_000L, top.bidPriceMicros());
        assertEquals(600_000L, top.askPriceMicros());
        assertEquals(7_000_000L, top.askQuantityMicros());
    }

    @Test
    void deltaBeforeSnapshotProducesSequenceGap() {
        OrderBookDeltaEvent delta = delta("""
            {"type":"orderbook_delta","sid":2,"seq":3,"msg":{"market_ticker":"M","price_dollars":"0.5000","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """);
        BookUpdateResult result = new OrderBookState("M").applyDelta(delta);

        SequenceGapEvent gap = assertInstanceOf(SequenceGapEvent.class, result.generatedEvents().get(0));
        assertEquals("delta_before_snapshot", gap.reason());
    }

    @Test
    void missingSequenceProducesGapAndPausesMarket() {
        OrderBookState state = new OrderBookState("M");
        state.applySnapshot(snapshot("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M","yes_dollars_fp":[["0.4500","10.00"]],"no_dollars_fp":[["0.4000","7.00"]]}}
            """));
        BookUpdateResult result = state.applyDelta(delta("""
            {"type":"orderbook_delta","sid":2,"seq":4,"msg":{"market_ticker":"M","price_dollars":"0.4600","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """));

        SequenceGapEvent gap = assertInstanceOf(SequenceGapEvent.class, result.generatedEvents().get(0));
        assertEquals(3L, gap.expectedSequence());
        assertEquals(4L, gap.actualSequence());
        assertEquals("out_of_order_delta", gap.reason());
    }

    @Test
    void zeroLevelRemovalChangesTopOfBook() {
        OrderBookState state = new OrderBookState("M");
        state.applySnapshot(snapshot("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M","yes_dollars_fp":[["0.4500","10.00"],["0.5000","3.00"]],"no_dollars_fp":[["0.4000","7.00"]]}}
            """));
        BookUpdateResult result = state.applyDelta(delta("""
            {"type":"orderbook_delta","sid":2,"seq":3,"msg":{"market_ticker":"M","price_dollars":"0.5000","delta_fp":"-3.00","side":"yes","ts_ms":1}}
            """));

        TopOfBookUpdate top = assertInstanceOf(TopOfBookUpdate.class, result.generatedEvents().get(0));
        assertEquals(450_000L, top.bidPriceMicros());
    }

    private OrderBookSnapshotEvent snapshot(String json) {
        return (OrderBookSnapshotEvent) parser.parseWebSocketMessage(json).canonicalEvents().get(0);
    }

    private OrderBookDeltaEvent delta(String json) {
        return (OrderBookDeltaEvent) parser.parseWebSocketMessage(json).canonicalEvents().get(0);
    }
}
