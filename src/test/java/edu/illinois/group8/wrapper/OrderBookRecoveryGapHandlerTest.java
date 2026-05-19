package edu.illinois.group8.wrapper;

import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.wrapper.OrderBookRecoveryController.RequestStatus;
import edu.illinois.group8.wrapper.OrderBookRecoveryGapHandler.Status;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookRecoveryGapHandlerTest {
    private static final String GAP_STREAM = "system.sequence_gaps";
    private static final String OTHER_STREAM = "canonical.trade";

    @Test
    void validSequenceGapPayloadSchedulesSnapshotWithoutRunningItInline() {
        Fixture fixture = new Fixture();

        OrderBookRecoveryGapHandler.Result result = fixture.handler.handlePayload(
            GAP_STREAM,
            payload("crossed_book", "M")
        );

        assertEquals(Status.HANDLED, result.status());
        assertEquals(RequestStatus.REQUEST_SCHEDULED, result.requestStatus());
        assertEquals(1, fixture.executor.pendingCount());
        assertTrue(fixture.requester.calls.isEmpty());
        fixture.assertPayloadStatus(Status.HANDLED, 1L);

        fixture.executor.runAll();

        assertEquals(1, fixture.requester.calls.size());
        SnapshotCall call = fixture.requester.calls.get(0);
        assertEquals(42L, call.sid());
        assertArrayEquals(new String[] {"M"}, call.marketTickers());
        assertEquals(500, call.timeoutMs());
    }

    @Test
    void nonGapStreamDoesNotRequestSnapshot() {
        Fixture fixture = new Fixture();

        OrderBookRecoveryGapHandler.Result result = fixture.handler.handlePayload(
            OTHER_STREAM,
            payload("crossed_book", "M")
        );

        assertEquals(Status.SKIPPED_NON_GAP_STREAM, result.status());
        assertEquals(null, result.requestStatus());
        assertEquals(0, fixture.executor.pendingCount());
        fixture.assertPayloadStatus(Status.SKIPPED_NON_GAP_STREAM, 1L);
    }

    @Test
    void nonSequenceGapEventDoesNotRequestSnapshot() {
        Fixture fixture = new Fixture();
        String payload = """
            {"event_id":"trade-1","event_type":"market_trade","schema_version":1,"stream_name":"canonical.trade","metadata":{"source":"kalshi","market_ticker":"M"}}
            """;

        OrderBookRecoveryGapHandler.Result result = fixture.handler.handlePayload(GAP_STREAM, payload);

        assertEquals(Status.SKIPPED_NON_GAP_EVENT, result.status());
        assertEquals(null, result.requestStatus());
        assertEquals(0, fixture.executor.pendingCount());
        fixture.assertPayloadStatus(Status.SKIPPED_NON_GAP_EVENT, 1L);
    }

    @Test
    void malformedOrEmptyPayloadDoesNotThrowOrRequestSnapshot() {
        Fixture fixture = new Fixture();

        assertEquals(Status.SKIPPED_MALFORMED_PAYLOAD, fixture.handler.handlePayload(GAP_STREAM, "{").status());
        assertEquals(Status.SKIPPED_MALFORMED_PAYLOAD, fixture.handler.handlePayload(GAP_STREAM, "").status());
        assertEquals(Status.SKIPPED_MALFORMED_PAYLOAD, fixture.handler.handlePayload(GAP_STREAM, null).status());
        assertEquals(0, fixture.executor.pendingCount());
        fixture.assertPayloadStatus(Status.SKIPPED_MALFORMED_PAYLOAD, 3L);
    }

    @Test
    void unsupportedReasonReturnsControllerSkipStatusWithoutRequestingSnapshot() {
        Fixture fixture = new Fixture();

        OrderBookRecoveryGapHandler.Result result = fixture.handler.handlePayload(
            GAP_STREAM,
            payload("manual_pause", "M")
        );

        assertEquals(Status.HANDLED, result.status());
        assertEquals(RequestStatus.SKIPPED_UNSUPPORTED_REASON, result.requestStatus());
        assertEquals(0, fixture.executor.pendingCount());
        fixture.assertPayloadStatus(Status.HANDLED, 1L);
    }

    private static String payload(String reason, String marketTicker) {
        return new JsonCanonicalSerializer().toJson(new SequenceGapEvent(
            "gap-" + reason,
            new EventMetadata(
                "kalshi",
                "orderbook_delta",
                42L,
                4L,
                marketTicker,
                null,
                1L,
                100L,
                null,
                "raw-1",
                null
            ),
            3L,
            4L,
            reason,
            "pause_market_and_request_fresh_snapshot"
        ));
    }

    private static final class Fixture {
        private final FakeExecutor executor = new FakeExecutor();
        private final RecordingSnapshotRequester requester = new RecordingSnapshotRequester();
        private final BackendMetrics backendMetrics = new BackendMetrics();
        private final OrderBookRecoveryMetrics metrics = new OrderBookRecoveryMetrics(backendMetrics);
        private final OrderBookRecoveryController controller = new OrderBookRecoveryController(executor, 500, metrics);
        private final OrderBookRecoveryGapHandler handler = new OrderBookRecoveryGapHandler(controller, metrics);

        private Fixture() {
            controller.registerMarket("M", 42L, requester);
        }

        private void assertPayloadStatus(Status status, long expected) {
            assertEquals(expected, backendMetrics.get(
                "orderbook_recovery_gap_payloads_total",
                BackendMetrics.labels("service", "wsclient", "status", status.name().toLowerCase())
            ));
        }
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

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }

    private record SnapshotCall(long sid, String[] marketTickers, int timeoutMs) {
    }

    private static final class RecordingSnapshotRequester implements OrderBookRecoveryController.SnapshotRequester {
        private final List<SnapshotCall> calls = new ArrayList<>();

        @Override
        public void requestSnapshotAndAwaitOk(long sid, String[] marketTickers, int timeoutMs) {
            calls.add(new SnapshotCall(sid, marketTickers, timeoutMs));
        }
    }
}
