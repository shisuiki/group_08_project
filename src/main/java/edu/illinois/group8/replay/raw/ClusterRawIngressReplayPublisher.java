package edu.illinois.group8.replay.raw;

import edu.illinois.group8.cluster.ClientClusterOrchestrator;
import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.ingress.KalshiIngressEnvelope;

public class ClusterRawIngressReplayPublisher implements RawIngressReplayPublisher {
    private final ClusterSink cluster;

    public ClusterRawIngressReplayPublisher(BackendConfig config) {
        this(new ClientClusterSink(new ClientClusterOrchestrator(config.clusterAddresses(), config.hostIp())));
    }

    ClusterRawIngressReplayPublisher(ClusterSink cluster) {
        this.cluster = cluster;
    }

    @Override
    public boolean publish(RawReplayEvent event, String replayId) {
        long receiveTsNs = event.receiveTsNs() == null ? System.nanoTime() : event.receiveTsNs();
        byte[] payload = KalshiIngressEnvelope.wrapBytes(
            event.rawPayload(),
            receiveTsNs,
            null,
            event.connectionId(),
            replayId
        );
        return cluster.writeToCluster(payload);
    }

    @Override
    public void close() {
        cluster.close();
    }

    interface ClusterSink {
        boolean writeToCluster(byte[] message);

        void close();
    }

    private record ClientClusterSink(ClientClusterOrchestrator cluster) implements ClusterSink {
        @Override
        public boolean writeToCluster(byte[] message) {
            return cluster.writeToCluster(message);
        }

        @Override
        public void close() {
            cluster.close();
        }
    }
}
