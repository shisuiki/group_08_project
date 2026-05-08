package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceSequenceMonitorTest {
    private final KalshiCanonicalParser parser = new KalshiCanonicalParser();

    @Test
    void contiguousSubscriptionSequenceAcrossMarketsDoesNotProduceGap() {
        SourceSequenceMonitor monitor = new SourceSequenceMonitor();

        assertTrue(monitor.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M1","yes_dollars_fp":[],"no_dollars_fp":[]}}
            """)).isEmpty());
        assertTrue(monitor.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":3,"msg":{"market_ticker":"M2","yes_dollars_fp":[],"no_dollars_fp":[]}}
            """)).isEmpty());
        assertTrue(monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":4,"msg":{"market_ticker":"M1","price_dollars":"0.4600","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """)).isEmpty());
    }

    @Test
    void missingSubscriptionSequenceProducesSourceGap() {
        SourceSequenceMonitor monitor = new SourceSequenceMonitor();
        monitor.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M1","yes_dollars_fp":[],"no_dollars_fp":[]}}
            """));

        List<CanonicalEvent> generated = monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":4,"msg":{"market_ticker":"M1","price_dollars":"0.4600","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """));

        SequenceGapEvent gap = assertInstanceOf(SequenceGapEvent.class, generated.get(0));
        assertEquals(3L, gap.expectedSequence());
        assertEquals(4L, gap.actualSequence());
        assertEquals("source_sequence_gap", gap.reason());
    }

    private CanonicalEvent event(String json) {
        return parser.parseWebSocketMessage(json).canonicalEvents().get(0);
    }
}
