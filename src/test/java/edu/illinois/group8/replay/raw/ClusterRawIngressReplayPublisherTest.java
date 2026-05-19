package edu.illinois.group8.replay.raw;

import edu.illinois.group8.ingress.KalshiIngressEnvelope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterRawIngressReplayPublisherTest {
    @Test
    void publishWritesEnvelopeBytesToCluster() {
        RecordingClusterSink sink = new RecordingClusterSink(true);
        ClusterRawIngressReplayPublisher publisher = new ClusterRawIngressReplayPublisher(sink);
        RawReplayEvent event = event("{\"type\":\"ticker\",\"msg\":{\"market_ticker\":\"M\"}}", 123L, "conn-1");

        assertTrue(publisher.publish(event, "replay-1"));

        assertEquals((byte) 'K', sink.lastMessage[0]);
        KalshiIngressEnvelope envelope = KalshiIngressEnvelope.parse(sink.lastMessage, -1L);
        assertTrue(envelope.enveloped());
        assertEquals(event.rawPayload(), envelope.rawPayload());
        assertEquals("replay-1", envelope.replayId());
        assertEquals("conn-1", envelope.connectionId());
        assertEquals(123L, envelope.receiveTsNs());
    }

    @Test
    void publishReturnsClusterWriteResult() {
        RecordingClusterSink sink = new RecordingClusterSink(false);
        ClusterRawIngressReplayPublisher publisher = new ClusterRawIngressReplayPublisher(sink);

        assertFalse(publisher.publish(event("{\"type\":\"ticker\"}", 123L, "conn-1"), "replay-1"));
        assertEquals(1, sink.writeCalls);
    }

    @Test
    void closeClosesClusterSink() {
        RecordingClusterSink sink = new RecordingClusterSink(true);
        ClusterRawIngressReplayPublisher publisher = new ClusterRawIngressReplayPublisher(sink);

        publisher.close();

        assertEquals(1, sink.closeCalls);
    }

    private static RawReplayEvent event(String rawPayload, Long receiveTsNs, String connectionId) {
        return new RawReplayEvent(rawPayload, receiveTsNs, connectionId, 7L, "raw-1", "M", "raw_ws_events", "pos-1");
    }

    private static final class RecordingClusterSink implements ClusterRawIngressReplayPublisher.ClusterSink {
        private final boolean result;
        private byte[] lastMessage = new byte[0];
        private int writeCalls;
        private int closeCalls;

        private RecordingClusterSink(boolean result) {
            this.result = result;
        }

        @Override
        public boolean writeToCluster(byte[] message) {
            writeCalls++;
            lastMessage = message;
            return result;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
