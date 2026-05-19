package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void restoredWatermarkCatchesDuplicateAndDoesNotRewind() {
        SourceSequenceMonitor monitor = new SourceSequenceMonitor(Map.of(2L, 4L));

        List<CanonicalEvent> generated = monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":3,"msg":{"market_ticker":"M1","price_dollars":"0.4600","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """));

        SequenceGapEvent gap = onlyGap(generated);
        assertEquals(5L, gap.expectedSequence());
        assertEquals(3L, gap.actualSequence());
        assertEquals("non_monotonic_source_sequence", gap.reason());
        assertEquals("inspect_source_subscription_and_reconnect", gap.recoveryAction());

        assertTrue(monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":5,"msg":{"market_ticker":"M1","price_dollars":"0.4700","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """)).isEmpty());
    }

    @Test
    void restoredWatermarkCatchesForwardGapAndAdvances() {
        SourceSequenceMonitor monitor = new SourceSequenceMonitor(Map.of(2L, 4L));

        List<CanonicalEvent> generated = monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":7,"msg":{"market_ticker":"M1","price_dollars":"0.4600","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """));

        SequenceGapEvent gap = onlyGap(generated);
        assertEquals(5L, gap.expectedSequence());
        assertEquals(7L, gap.actualSequence());
        assertEquals("source_sequence_gap", gap.reason());

        assertTrue(monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":8,"msg":{"market_ticker":"M1","price_dollars":"0.4700","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """)).isEmpty());
    }

    @Test
    void restoredWatermarkAllowsContiguousNextSequence() {
        SourceSequenceMonitor monitor = new SourceSequenceMonitor(Map.of(2L, 4L));

        assertTrue(monitor.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":5,"msg":{"market_ticker":"M1","price_dollars":"0.4600","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """)).isEmpty());
    }

    @Test
    void snapshotAndRestoreDefensivelyCopyWatermarks() {
        SourceSequenceMonitor monitor = new SourceSequenceMonitor();
        monitor.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M1","yes_dollars_fp":[],"no_dollars_fp":[]}}
            """));

        Map<Long, Long> snapshot = monitor.snapshotWatermarks();
        assertEquals(Map.of(2L, 2L), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put(2L, 99L));

        monitor.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":3,"msg":{"market_ticker":"M2","yes_dollars_fp":[],"no_dollars_fp":[]}}
            """));
        assertEquals(Map.of(2L, 2L), snapshot);

        Map<Long, Long> restoredWatermarks = new HashMap<>();
        restoredWatermarks.put(2L, 4L);
        SourceSequenceMonitor restored = new SourceSequenceMonitor(restoredWatermarks);
        restoredWatermarks.put(2L, 99L);

        assertTrue(restored.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":5,"msg":{"market_ticker":"M1","price_dollars":"0.4700","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """)).isEmpty());
    }

    @Test
    void invalidRestoreInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> new SourceSequenceMonitor(null));

        Map<Long, Long> nullSubscriptionId = new HashMap<>();
        nullSubscriptionId.put(null, 1L);
        assertThrows(IllegalArgumentException.class, () -> new SourceSequenceMonitor(nullSubscriptionId));

        Map<Long, Long> negativeSubscriptionId = new HashMap<>();
        negativeSubscriptionId.put(-1L, 1L);
        assertThrows(IllegalArgumentException.class, () -> new SourceSequenceMonitor(negativeSubscriptionId));

        Map<Long, Long> nullSequence = new HashMap<>();
        nullSequence.put(2L, null);
        assertThrows(IllegalArgumentException.class, () -> new SourceSequenceMonitor(nullSequence));

        assertThrows(IllegalArgumentException.class, () -> new SourceSequenceMonitor(Map.of(2L, -1L)));
    }

    private CanonicalEvent event(String json) {
        return parser.parseWebSocketMessage(json).canonicalEvents().get(0);
    }

    private static SequenceGapEvent onlyGap(List<CanonicalEvent> generated) {
        assertEquals(1, generated.size());
        return assertInstanceOf(SequenceGapEvent.class, generated.get(0));
    }
}
