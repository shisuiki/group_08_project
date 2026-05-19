package edu.illinois.group8.cluster;

import edu.illinois.group8.metrics.BackendMetrics;
import java.nio.charset.StandardCharsets;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientClusterOrchestratorTest {
    @Test
    void defaultMaxOfferAttemptsIsDropFirst() {
        assertEquals(1, ClientClusterOrchestrator.DEFAULT_MAX_OFFER_ATTEMPTS);
    }

    @Test
    void writeToClusterSucceedsImmediately() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(1L);
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        ClientClusterOrchestrator orchestrator = orchestrator(
            ingressClient,
            idleStrategy,
            metrics,
            ClientClusterOrchestrator.DEFAULT_MAX_OFFER_ATTEMPTS
        );
        byte[] message = new byte[] {0, (byte) 0xff, 0x41};

        assertTrue(orchestrator.writeToCluster(message));

        assertEquals(1, ingressClient.offerCalls);
        assertEquals(message.length, ingressClient.lastLength);
        assertArrayEquals(message, ingressClient.lastPayload);
        assertEquals(0, ingressClient.pollEgressCalls);
        assertEquals(0, idleStrategy.idleCalls);
        assertEquals(0, idleStrategy.resetCalls);
        assertEquals(0L, metrics.get(ClientClusterOrchestrator.OFFER_FAILED_COUNTER));
        assertEquals(0L, metrics.get(ClientClusterOrchestrator.DROPPED_COUNTER));
    }

    @Test
    void defaultWriteToClusterDropsAfterSingleOfferFailure() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(-1L);
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        ClientClusterOrchestrator orchestrator = orchestrator(
            ingressClient,
            idleStrategy,
            metrics,
            ClientClusterOrchestrator.DEFAULT_MAX_OFFER_ATTEMPTS
        );

        assertFalse(orchestrator.writeToCluster("drop".getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, ingressClient.offerCalls);
        assertEquals(0, ingressClient.pollEgressCalls);
        assertEquals(0, idleStrategy.idleCalls);
        assertEquals(0, idleStrategy.resetCalls);
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.OFFER_FAILED_COUNTER));
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.DROPPED_COUNTER));
    }

    @Test
    void explicitBoundedRetryCanSucceedAfterPollingEgress() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(-1L, 1L);
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        ClientClusterOrchestrator orchestrator = orchestrator(ingressClient, idleStrategy, metrics, 3);

        assertTrue(orchestrator.writeToCluster("retry".getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, ingressClient.offerCalls);
        assertEquals(1, ingressClient.pollEgressCalls);
        assertEquals(1, idleStrategy.idleCalls);
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.OFFER_FAILED_COUNTER));
        assertEquals(0L, metrics.get(ClientClusterOrchestrator.DROPPED_COUNTER));
    }

    @Test
    void explicitBoundedRetryStopsAtFixedAttemptCount() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(-1L);
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        ClientClusterOrchestrator orchestrator = orchestrator(ingressClient, idleStrategy, metrics, 5);

        assertFalse(orchestrator.writeToCluster("bounded".getBytes(StandardCharsets.UTF_8)));

        assertEquals(5, ingressClient.offerCalls);
        assertEquals(4, ingressClient.pollEgressCalls);
        assertEquals(4, idleStrategy.idleCalls);
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(5L, metrics.get(ClientClusterOrchestrator.OFFER_FAILED_COUNTER));
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.DROPPED_COUNTER));
    }

    @Test
    void interruptedCallerDropsAndPreservesInterruptFlag() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(-1L);
        ingressClient.interruptAfterOffer = true;
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        ClientClusterOrchestrator orchestrator = orchestrator(
            ingressClient,
            idleStrategy,
            metrics,
            ClientClusterOrchestrator.DEFAULT_MAX_OFFER_ATTEMPTS
        );

        try {
            assertFalse(orchestrator.writeToCluster("interrupt".getBytes(StandardCharsets.UTF_8)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, ingressClient.offerCalls);
        assertEquals(0, ingressClient.pollEgressCalls);
        assertEquals(0, idleStrategy.idleCalls);
        assertEquals(0, idleStrategy.resetCalls);
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.OFFER_FAILED_COUNTER));
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.DROPPED_COUNTER));
    }

    @Test
    void alreadyInterruptedCallerDropsWithoutOfferingAndPreservesInterruptFlag() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(1L);
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        ClientClusterOrchestrator orchestrator = orchestrator(
            ingressClient,
            idleStrategy,
            metrics,
            ClientClusterOrchestrator.DEFAULT_MAX_OFFER_ATTEMPTS
        );

        Thread.currentThread().interrupt();
        try {
            assertFalse(orchestrator.writeToCluster("already-interrupted".getBytes(StandardCharsets.UTF_8)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, ingressClient.offerCalls);
        assertEquals(0, ingressClient.pollEgressCalls);
        assertEquals(0, idleStrategy.idleCalls);
        assertEquals(0, idleStrategy.resetCalls);
        assertEquals(0L, metrics.get(ClientClusterOrchestrator.OFFER_FAILED_COUNTER));
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.DROPPED_COUNTER));
    }

    private static ClientClusterOrchestrator orchestrator(
        FakeIngressClient ingressClient,
        BackendMetrics metrics,
        int maxOfferAttempts
    ) {
        return orchestrator(ingressClient, new RecordingIdleStrategy(), metrics, maxOfferAttempts);
    }

    private static ClientClusterOrchestrator orchestrator(
        FakeIngressClient ingressClient,
        IdleStrategy idleStrategy,
        BackendMetrics metrics,
        int maxOfferAttempts
    ) {
        return new ClientClusterOrchestrator(ingressClient, idleStrategy, maxOfferAttempts, metrics);
    }

    private static final class FakeIngressClient implements ClientClusterOrchestrator.IngressClient {
        private final long[] results;
        private int offerCalls;
        private int pollEgressCalls;
        private int lastLength;
        private byte[] lastPayload = new byte[0];
        private boolean interruptAfterOffer;

        private FakeIngressClient(long... results) {
            this.results = results;
        }

        @Override
        public long offer(MutableDirectBuffer buffer, int offset, int length) {
            offerCalls++;
            lastLength = length;
            lastPayload = new byte[length];
            buffer.getBytes(offset, lastPayload);
            if (interruptAfterOffer) {
                Thread.currentThread().interrupt();
            }
            return results[Math.min(offerCalls - 1, results.length - 1)];
        }

        @Override
        public int pollEgress() {
            pollEgressCalls++;
            return 0;
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingIdleStrategy implements IdleStrategy {
        private int idleCalls;
        private int resetCalls;

        @Override
        public void idle(int workCount) {
            idleCalls++;
        }

        @Override
        public void idle() {
            idleCalls++;
        }

        @Override
        public void reset() {
            resetCalls++;
        }
    }
}
