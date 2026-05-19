package edu.illinois.group8.wrapper;

import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.wrapper.OrderBookRecoveryController.RequestStatus;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookRecoveryControllerTest {
    @Test
    void normalGapSchedulesSnapshotRequestWithoutRunningItInline() {
        FakeExecutor executor = new FakeExecutor();
        RecordingRequester requester = new RecordingRequester();
        OrderBookRecoveryController controller = controller(executor);
        controller.registerMarket("M", 11L, requester);

        RequestStatus status = controller.handleGap(gap("crossed_book", "M"));

        assertEquals(RequestStatus.REQUEST_SCHEDULED, status);
        assertEquals(1, executor.pendingCount());
        assertTrue(requester.calls.isEmpty());

        executor.runNext();

        assertEquals(1, requester.calls.size());
        SnapshotCall call = requester.calls.get(0);
        assertEquals(11L, call.sid());
        assertArrayEquals(new String[] {"M"}, call.marketTickers());
        assertEquals(250, call.timeoutMs());
    }

    @Test
    void duplicateInFlightGapDoesNotScheduleAnotherRequest() {
        FakeExecutor executor = new FakeExecutor();
        OrderBookRecoveryController controller = controller(executor);
        controller.registerMarket("M", 11L, new RecordingRequester());

        assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(gap("source_sequence_gap", "M")));
        assertEquals(RequestStatus.SKIPPED_IN_FLIGHT, controller.handleGap(gap("crossed_book", "M")));
        assertEquals(1, executor.pendingCount());
    }

    @Test
    void successfulRequestClearsInFlightSoLaterGapCanScheduleAgain() {
        FakeExecutor executor = new FakeExecutor();
        RecordingRequester requester = new RecordingRequester();
        OrderBookRecoveryController controller = controller(executor);
        controller.registerMarket("M", 11L, requester);

        assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(gap("source_sequence_gap", "M")));
        executor.runNext();

        assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(gap("source_sequence_gap", "M")));
        executor.runNext();

        assertEquals(2, requester.calls.size());
    }

    @Test
    void failedRequestClearsInFlightAndDoesNotEscapeTask() {
        FakeExecutor executor = new FakeExecutor();
        ThrowingRequester requester = new ThrowingRequester(new IllegalStateException("ws failed"));
        OrderBookRecoveryController controller = controller(executor);
        controller.registerMarket("M", 11L, requester);

        assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(gap("crossed_book", "M")));
        executor.runNext();

        assertEquals(1, requester.calls);
        assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(gap("crossed_book", "M")));
        assertEquals(1, executor.pendingCount());
    }

    @Test
    void interruptedRequestRestoresInterruptFlagAndClearsInFlight() {
        Thread.interrupted();
        FakeExecutor executor = new FakeExecutor();
        ThrowingRequester requester = new ThrowingRequester(new InterruptedException("interrupted"));
        OrderBookRecoveryController controller = controller(executor);
        controller.registerMarket("M", 11L, requester);

        try {
            assertFalse(Thread.currentThread().isInterrupted());
            assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(gap("crossed_book", "M")));
            executor.runNext();

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(gap("crossed_book", "M")));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unsupportedReasonUnknownTickerAndMissingMetadataSkipWithoutRequest() {
        FakeExecutor executor = new FakeExecutor();
        OrderBookRecoveryController controller = controller(executor);
        controller.registerMarket("M", 11L, new RecordingRequester());

        assertEquals(RequestStatus.SKIPPED_UNSUPPORTED_REASON, controller.handleGap(gap("manual_pause", "M")));
        assertEquals(RequestStatus.SKIPPED_UNKNOWN_MARKET, controller.handleGap(gap("crossed_book", "UNKNOWN")));
        assertEquals(RequestStatus.SKIPPED_MISSING_METADATA, controller.handleGap(gap("crossed_book", null)));
        assertEquals(RequestStatus.SKIPPED_MISSING_METADATA, controller.handleGap(gapWithMetadata("crossed_book", null)));
        assertEquals(RequestStatus.SKIPPED_UNSUPPORTED_REASON, controller.handleGap(null));
        assertEquals(0, executor.pendingCount());
    }

    @Test
    void allSupportedReasonsScheduleSnapshotRequests() {
        List<String> reasons = List.of(
            "source_sequence_gap",
            "non_monotonic_source_sequence",
            "non_monotonic_orderbook_sequence",
            "crossed_book",
            "delta_before_snapshot",
            "market_paused_for_snapshot_recovery"
        );
        FakeExecutor executor = new FakeExecutor();
        OrderBookRecoveryController controller = controller(executor);
        RecordingRequester requester = new RecordingRequester();
        controller.registerMarket("M", 11L, requester);

        for (String reason : reasons) {
            assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(gap(reason, "M")));
            executor.runNext();
        }

        assertEquals(reasons.size(), requester.calls.size());
    }

    private static OrderBookRecoveryController controller(FakeExecutor executor) {
        return new OrderBookRecoveryController(executor, 250);
    }

    private static SequenceGapEvent gap(String reason, String marketTicker) {
        return gapWithMetadata(reason, metadata(marketTicker));
    }

    private static SequenceGapEvent gapWithMetadata(String reason, EventMetadata metadata) {
        return new SequenceGapEvent(
            "gap-" + reason,
            metadata,
            3L,
            4L,
            reason,
            "pause_market_and_request_fresh_snapshot"
        );
    }

    private static EventMetadata metadata(String marketTicker) {
        return new EventMetadata(
            "kalshi",
            "orderbook_delta",
            11L,
            4L,
            marketTicker,
            null,
            1L,
            100L,
            null,
            "raw-1",
            null
        );
    }

    private static final class FakeExecutor implements java.util.concurrent.Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private int pendingCount() {
            return tasks.size();
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }

    private record SnapshotCall(long sid, String[] marketTickers, int timeoutMs) {
    }

    private static final class RecordingRequester implements OrderBookRecoveryController.SnapshotRequester {
        private final List<SnapshotCall> calls = new ArrayList<>();

        @Override
        public void requestSnapshotAndAwaitOk(long sid, String[] marketTickers, int timeoutMs) {
            calls.add(new SnapshotCall(sid, marketTickers, timeoutMs));
        }
    }

    private static final class ThrowingRequester implements OrderBookRecoveryController.SnapshotRequester {
        private final Exception exception;
        private int calls;

        private ThrowingRequester(Exception exception) {
            this.exception = exception;
        }

        @Override
        public void requestSnapshotAndAwaitOk(long sid, String[] marketTickers, int timeoutMs)
            throws InterruptedException {
            calls++;
            if (exception instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw (RuntimeException) exception;
        }
    }
}
