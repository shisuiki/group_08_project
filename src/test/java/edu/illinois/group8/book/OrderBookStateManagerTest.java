package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.canonical.TopOfBookUpdate;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void recoveryCheckpointsDefensivelyCopyKnownMarkets() {
        OrderBookStateManager manager = new OrderBookStateManager();
        manager.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M1","yes_dollars_fp":[["0.4500","10.00"]],"no_dollars_fp":[["0.4000","7.00"]]}}
            """));
        manager.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":3,"msg":{"market_ticker":"M2","yes_dollars_fp":[["0.4400","8.00"]],"no_dollars_fp":[["0.4100","6.00"]]}}
            """));

        var checkpoints = manager.recoveryCheckpoints();

        assertTrue(checkpoints.contains(new OrderBookRecoveryCheckpoint("M1", 2L)));
        assertTrue(checkpoints.contains(new OrderBookRecoveryCheckpoint("M2", 3L)));
        assertThrows(UnsupportedOperationException.class, () ->
            checkpoints.add(new OrderBookRecoveryCheckpoint("M3", 4L)));

        manager.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":4,"msg":{"market_ticker":"M1","price_dollars":"0.4600","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """));

        assertTrue(checkpoints.contains(new OrderBookRecoveryCheckpoint("M1", 2L)));
        assertFalse(checkpoints.contains(new OrderBookRecoveryCheckpoint("M1", 4L)));
    }

    @Test
    void restorePausedPreservesExpectedSequenceAndSuppressesDerivedTop() {
        OrderBookStateManager manager = new OrderBookStateManager();
        ArrayList<OrderBookRecoveryCheckpoint> checkpoints = new ArrayList<>();
        checkpoints.add(new OrderBookRecoveryCheckpoint("M", 4L));
        manager.restorePaused(checkpoints);
        checkpoints.clear();

        BookUpdateResult result = manager.apply(event("""
            {"type":"orderbook_delta","sid":2,"seq":5,"msg":{"market_ticker":"M","price_dollars":"0.4700","delta_fp":"1.00","side":"yes","ts_ms":1}}
            """));

        assertEquals(1, result.generatedEvents().size());
        SequenceGapEvent gap = assertInstanceOf(SequenceGapEvent.class, result.generatedEvents().get(0));
        assertEquals(Long.valueOf(5L), gap.expectedSequence());
        assertEquals(Long.valueOf(5L), gap.actualSequence());
        assertEquals("market_paused_for_snapshot_recovery", gap.reason());
        OrderBookState state = manager.getState("M");
        assertFalse(state.hasSnapshot());
        assertTrue(state.pausedForRecovery());
        assertFalse(state.safeForDerivedTopOfBook());
        assertEquals(Long.valueOf(4L), state.lastSequence());
    }

    @Test
    void pauseAllForSnapshotRecoveryPausesExistingBooks() {
        OrderBookStateManager manager = new OrderBookStateManager();
        manager.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"M1","yes_dollars_fp":[["0.4500","10.00"]],"no_dollars_fp":[["0.4000","7.00"]]}}
            """));
        manager.apply(event("""
            {"type":"orderbook_snapshot","sid":2,"seq":3,"msg":{"market_ticker":"M2","yes_dollars_fp":[["0.4400","8.00"]],"no_dollars_fp":[["0.4100","6.00"]]}}
            """));

        manager.pauseAllForSnapshotRecovery();

        assertTrue(manager.getState("M1").pausedForRecovery());
        assertTrue(manager.getState("M2").pausedForRecovery());
        assertFalse(manager.getState("M1").safeForDerivedTopOfBook());
        assertFalse(manager.getState("M2").safeForDerivedTopOfBook());
    }

    @Test
    void restorePausedRejectsInvalidCheckpoints() {
        OrderBookStateManager manager = new OrderBookStateManager();

        assertThrows(IllegalArgumentException.class, () -> manager.restorePaused(null));
        assertThrows(IllegalArgumentException.class, () -> manager.restorePaused(Arrays.asList(
            new OrderBookRecoveryCheckpoint("M", 1L),
            null
        )));
        assertThrows(IllegalArgumentException.class, () -> manager.restorePaused(Arrays.asList(
            new OrderBookRecoveryCheckpoint(null, 1L)
        )));
        assertThrows(IllegalArgumentException.class, () -> manager.restorePaused(Arrays.asList(
            new OrderBookRecoveryCheckpoint(" ", 1L)
        )));
        assertThrows(IllegalArgumentException.class, () -> manager.restorePaused(Arrays.asList(
            new OrderBookRecoveryCheckpoint("M", -1L)
        )));
        assertThrows(IllegalArgumentException.class, () -> manager.restorePaused(Arrays.asList(
            new OrderBookRecoveryCheckpoint("M", 1L),
            new OrderBookRecoveryCheckpoint("M", 2L)
        )));

        manager.restorePaused(Arrays.asList(new OrderBookRecoveryCheckpoint("M", 4L)));
        assertThrows(IllegalArgumentException.class, () -> manager.restorePaused(Arrays.asList(
            new OrderBookRecoveryCheckpoint("M2", 1L),
            new OrderBookRecoveryCheckpoint("M2", 2L)
        )));
        assertEquals(new OrderBookRecoveryCheckpoint("M", 4L), manager.recoveryCheckpoints().get(0));
    }

    private CanonicalEvent event(String json) {
        return parser.parseWebSocketMessage(json).canonicalEvents().get(0);
    }
}
