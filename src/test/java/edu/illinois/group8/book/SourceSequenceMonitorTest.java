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

        assertEquals(1, generated.size());
        SequenceGapEvent gap = assertInstanceOf(SequenceGapEvent.class, generated.get(0));
        assertEquals(3L, gap.expectedSequence());
        assertEquals(4L, gap.actualSequence());
        assertEquals("source_sequence_gap", gap.reason());
        assertEquals("inspect_source_subscription_and_reconnect", gap.recoveryAction());
    }

    @Test
    void duplicateSequenceDoesNotRewindWatermark() {
        SourceSequenceMonitor monitor = new SourceSequenceMonitor();

        assertTrue(monitor.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M1","yes_dollars_fp":[],"no_dollars_fp":[]}}
            """)).isEmpty());
        assertTrue(monitor.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":3,"msg":{"market_ticker":"M2","yes_dollars_fp":[],"no_dollars_fp":[]}}
            """)).isEmpty());

        List<CanonicalEvent> duplicate = monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":2,"msg":{"market_ticker":"M1","price_dollars":"0.4600","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """));

        assertEquals(1, duplicate.size());
        SequenceGapEvent duplicateGap = assertInstanceOf(SequenceGapEvent.class, duplicate.get(0));
        assertEquals(4L, duplicateGap.expectedSequence());
        assertEquals(2L, duplicateGap.actualSequence());
        assertEquals("non_monotonic_source_sequence", duplicateGap.reason());
        assertEquals("inspect_source_subscription_and_reconnect", duplicateGap.recoveryAction());

        assertTrue(monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":4,"msg":{"market_ticker":"M1","price_dollars":"0.4700","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """)).isEmpty());
    }

    @Test
    void olderSequenceAfterForwardGapDoesNotRewindWatermark() {
        SourceSequenceMonitor monitor = new SourceSequenceMonitor();
        monitor.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M1","yes_dollars_fp":[],"no_dollars_fp":[]}}
            """));

        List<CanonicalEvent> forwardGap = monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":4,"msg":{"market_ticker":"M1","price_dollars":"0.4600","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """));
        assertEquals(1, forwardGap.size());
        SequenceGapEvent sourceGap = assertInstanceOf(SequenceGapEvent.class, forwardGap.get(0));
        assertEquals("source_sequence_gap", sourceGap.reason());

        List<CanonicalEvent> older = monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":3,"msg":{"market_ticker":"M1","price_dollars":"0.4700","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """));

        assertEquals(1, older.size());
        SequenceGapEvent olderGap = assertInstanceOf(SequenceGapEvent.class, older.get(0));
        assertEquals(5L, olderGap.expectedSequence());
        assertEquals(3L, olderGap.actualSequence());
        assertEquals("non_monotonic_source_sequence", olderGap.reason());

        assertTrue(monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":5,"msg":{"market_ticker":"M1","price_dollars":"0.4800","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """)).isEmpty());
    }

    private CanonicalEvent event(String json) {
        return parser.parseWebSocketMessage(json).canonicalEvents().get(0);
    }
}
