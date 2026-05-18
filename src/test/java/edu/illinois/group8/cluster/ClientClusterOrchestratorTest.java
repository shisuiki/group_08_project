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
    void writeToClusterSucceedsImmediately() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(1L);
        ClientClusterOrchestrator orchestrator = orchestrator(ingressClient, metrics, 3);

        assertTrue(orchestrator.writeToCluster("ok".getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, ingressClient.offerCalls);
        assertEquals(0L, metrics.get(ClientClusterOrchestrator.OFFER_FAILED_COUNTER));
        assertEquals(0L, metrics.get(ClientClusterOrchestrator.DROPPED_COUNTER));
    }

    @Test
    void writeToClusterDropsAfterBoundedFailures() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(-1L);
        ClientClusterOrchestrator orchestrator = orchestrator(ingressClient, metrics, 3);

        assertFalse(orchestrator.writeToCluster("drop".getBytes(StandardCharsets.UTF_8)));

        assertEquals(3, ingressClient.offerCalls);
        assertEquals(3L, metrics.get(ClientClusterOrchestrator.OFFER_FAILED_COUNTER));
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.DROPPED_COUNTER));
    }

    @Test
    void continuousOfferFailureStopsAtFixedAttemptCount() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(-1L);
        ClientClusterOrchestrator orchestrator = orchestrator(ingressClient, metrics, 5);

        assertFalse(orchestrator.writeToCluster("bounded".getBytes(StandardCharsets.UTF_8)));

        assertEquals(5, ingressClient.offerCalls);
        assertEquals(5L, metrics.get(ClientClusterOrchestrator.OFFER_FAILED_COUNTER));
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.DROPPED_COUNTER));
    }

    @Test
    void writeToClusterStringUsesUtf8ByteLength() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(1L);
        ClientClusterOrchestrator orchestrator = orchestrator(ingressClient, metrics, 3);
        String message = "é汉";
        byte[] expected = message.getBytes(StandardCharsets.UTF_8);

        assertTrue(orchestrator.writeToCluster(message));

        assertEquals(expected.length, ingressClient.lastLength);
        assertArrayEquals(expected, ingressClient.lastPayload);
    }

    @Test
    void interruptedCallerDropsAndPreservesInterruptFlag() {
        BackendMetrics metrics = new BackendMetrics();
        FakeIngressClient ingressClient = new FakeIngressClient(-1L);
        ingressClient.interruptAfterOffer = true;
        ClientClusterOrchestrator orchestrator = orchestrator(ingressClient, metrics, 3);

        try {
            assertFalse(orchestrator.writeToCluster("interrupt".getBytes(StandardCharsets.UTF_8)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, ingressClient.offerCalls);
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.OFFER_FAILED_COUNTER));
        assertEquals(1L, metrics.get(ClientClusterOrchestrator.DROPPED_COUNTER));
    }

    private static ClientClusterOrchestrator orchestrator(
        FakeIngressClient ingressClient,
        BackendMetrics metrics,
        int maxOfferAttempts
    ) {
        return new ClientClusterOrchestrator(ingressClient, new NoopIdleStrategy(), maxOfferAttempts, metrics);
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

    private static final class NoopIdleStrategy implements IdleStrategy {
        @Override
        public void idle(int workCount) {
        }

        @Override
        public void idle() {
        }

        @Override
        public void reset() {
        }
    }
}
