package edu.illinois.group8.replay.raw;

import edu.illinois.group8.cluster.ClientClusterOrchestrator;
import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.ingress.KalshiIngressEnvelope;

public class ClusterRawIngressReplayPublisher implements RawIngressReplayPublisher {
    private final ClientClusterOrchestrator cluster;

    public ClusterRawIngressReplayPublisher(BackendConfig config) {
        this.cluster = new ClientClusterOrchestrator(config.clusterAddresses(), config.hostIp());
    }

    @Override
    public boolean publish(RawReplayEvent event, String replayId) {
        long receiveTsNs = event.receiveTsNs() == null ? System.nanoTime() : event.receiveTsNs();
        String payload = KalshiIngressEnvelope.wrap(
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
}
