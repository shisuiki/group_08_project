package edu.illinois.group8.replay.raw;

import edu.illinois.group8.cluster.ClientClusterOrchestrator;
import edu.illinois.group8.config.BackendConfig;

public class ClusterRawIngressReplayPublisher implements RawIngressReplayPublisher {
    private final ClientClusterOrchestrator cluster;

    public ClusterRawIngressReplayPublisher(BackendConfig config) {
        this.cluster = new ClientClusterOrchestrator(config.clusterAddresses(), config.hostIp());
    }

    @Override
    public boolean publish(String rawPayload) {
        return cluster.writeToCluster(rawPayload);
    }

    @Override
    public void close() {
        cluster.close();
    }
}
