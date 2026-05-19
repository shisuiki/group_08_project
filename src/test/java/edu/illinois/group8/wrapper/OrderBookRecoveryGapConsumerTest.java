package edu.illinois.group8.wrapper;

import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.feature.CanonicalEnvelope;
import edu.illinois.group8.feature.CanonicalEnvelopeHandler;
import edu.illinois.group8.feature.CanonicalEnvelopeSource;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookRecoveryGapConsumerTest {
    @Test
    void pollOnceDispatchesGapEnvelopeAndOnlyQueuesSnapshotRequest() {
        Fixture fixture = new Fixture(new FakeSource(envelope("system.sequence_gaps", gapPayload("crossed_book", "M"))));

        int polled = fixture.consumer.pollOnce();

        assertEquals(1, polled);
        assertEquals(1, fixture.source.pollCalls);
        assertEquals(1, fixture.executor.pendingCount());
        assertTrue(fixture.requester.calls.isEmpty());

        fixture.executor.runAll();

        assertEquals(1, fixture.requester.calls.size());
        SnapshotCall call = fixture.requester.calls.get(0);
        assertEquals(42L, call.sid());
        assertArrayEquals(new String[] {"M"}, call.marketTickers());
        assertEquals(500, call.timeoutMs());
    }

    @Test
    void nonGapEnvelopeIsPolledButDoesNotRequestSnapshot() {
        String payload = """
            {"event_id":"trade-1","event_type":"sequence_gap","schema_version":1,"stream_name":"canonical.trade","metadata":{"source":"kalshi","market_ticker":"M"}}
            """;
        Fixture fixture = new Fixture(new FakeSource(envelope("canonical.trade", payload)));

        assertEquals(1, fixture.consumer.pollOnce());

        assertEquals(0, fixture.executor.pendingCount());
        assertTrue(fixture.requester.calls.isEmpty());
    }

    @Test
    void runLoopSleepsOnEmptyPollAndStopExits() {
        FakeSource source = new FakeSource();
        TestSleeper sleeper = new TestSleeper();
        Fixture fixture = new Fixture(source, sleeper);
        sleeper.onSleep = fixture.consumer::stop;

        fixture.consumer.runUntilStopped();

        assertEquals(1, source.pollCalls);
        assertEquals(List.of(25L), sleeper.sleeps);
    }

    @Test
    void interruptedSleepRestoresInterruptFlagAndExits() {
        Thread.interrupted();
        FakeSource source = new FakeSource();
        TestSleeper sleeper = new TestSleeper();
        sleeper.interrupt = true;
        Fixture fixture = new Fixture(source, sleeper);

        try {
            fixture.consumer.runUntilStopped();

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, source.pollCalls);
            assertEquals(List.of(25L), sleeper.sleeps);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sourcePollRuntimeExceptionEscapes() {
        RuntimeException failure = new RuntimeException("source failed");
        ThrowingSource source = new ThrowingSource(failure);
        Fixture fixture = new Fixture(source);

        RuntimeException thrown = assertThrows(RuntimeException.class, fixture.consumer::pollOnce);

        assertEquals(failure, thrown);
    }

    @Test
    void closeStopsAndClosesSourceOnce() {
        Fixture fixture = new Fixture(new FakeSource());

        fixture.consumer.close();
        fixture.consumer.close();

        assertEquals(1, fixture.source.closeCalls);
    }

    @Test
    void constructorRejectsInvalidArguments() {
        Fixture fixture = new Fixture(new FakeSource());

        assertThrows(IllegalArgumentException.class, () ->
            new OrderBookRecoveryGapConsumer(fixture.source, fixture.handler, 0, 25L));
        assertThrows(IllegalArgumentException.class, () ->
            new OrderBookRecoveryGapConsumer(fixture.source, fixture.handler, 1, -1L));
    }

    private static CanonicalEnvelope envelope(String streamName, String payload) {
        return CanonicalEnvelope.fromPayload(streamName, payload, new JsonCanonicalSerializer().mapper());
    }

    private static String gapPayload(String reason, String marketTicker) {
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
        private final OrderBookRecoveryController controller = new OrderBookRecoveryController(executor, 500);
        private final OrderBookRecoveryGapHandler handler = new OrderBookRecoveryGapHandler(controller);
        private final FakeSource source;
        private final OrderBookRecoveryGapConsumer consumer;

        private Fixture(FakeSource source) {
            this(source, new TestSleeper());
        }

        private Fixture(FakeSource source, TestSleeper sleeper) {
            this.source = source;
            controller.registerMarket("M", 42L, requester);
            this.consumer = new OrderBookRecoveryGapConsumer(source, handler, 2, 25L, sleeper);
        }
    }

    private static class FakeSource implements CanonicalEnvelopeSource {
        private final Queue<CanonicalEnvelope> envelopes = new ArrayDeque<>();
        private int pollCalls;
        private int closeCalls;

        private FakeSource(CanonicalEnvelope... envelopes) {
            this.envelopes.addAll(List.of(envelopes));
        }

        @Override
        public int poll(CanonicalEnvelopeHandler handler, int fragmentLimit) {
            pollCalls++;
            int fragments = 0;
            while (fragments < fragmentLimit && !envelopes.isEmpty()) {
                handler.onEvent(envelopes.remove());
                fragments++;
            }
            return fragments;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private static final class ThrowingSource extends FakeSource {
        private final RuntimeException failure;

        private ThrowingSource(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public int poll(CanonicalEnvelopeHandler handler, int fragmentLimit) {
            throw failure;
        }
    }

    private static final class TestSleeper implements OrderBookRecoveryGapConsumer.Sleeper {
        private final List<Long> sleeps = new ArrayList<>();
        private Runnable onSleep = () -> {
        };
        private boolean interrupt;

        @Override
        public void sleep(long millis) throws InterruptedException {
            sleeps.add(millis);
            onSleep.run();
            if (interrupt) {
                throw new InterruptedException("test interrupt");
            }
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
