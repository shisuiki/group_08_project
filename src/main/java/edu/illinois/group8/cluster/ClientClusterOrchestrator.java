package edu.illinois.group8.cluster;

import edu.illinois.group8.metrics.BackendMetrics;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;

/**
 * Used for communication with the Aeron Cluster's ingress channels.
 */
public class ClientClusterOrchestrator {
    static final int DEFAULT_MAX_OFFER_ATTEMPTS = 1;
    static final String OFFER_FAILED_COUNTER = "cluster_ingress_offer_failed_total";
    static final String DROPPED_COUNTER = "cluster_ingress_dropped_total";

    private final IngressClient ingressClient;
    private final IdleStrategy idleStrategy;
    private final MutableDirectBuffer buf = new ExpandableArrayBuffer();
    private final int maxOfferAttempts;
    private final BackendMetrics metrics;

    public ClientClusterOrchestrator(List<String> hostnames, String ip) {
        this(hostnames, ip, new BackendMetrics());
    }

    public ClientClusterOrchestrator(List<String> hostnames, String ip, BackendMetrics metrics) {
        this(
            connectCluster(hostnames, ip),
            new BackoffIdleStrategy(),
            DEFAULT_MAX_OFFER_ATTEMPTS,
            Objects.requireNonNull(metrics, "metrics")
        );
    }

    ClientClusterOrchestrator(
        IngressClient ingressClient,
        IdleStrategy idleStrategy,
        int maxOfferAttempts,
        BackendMetrics metrics
    ) {
        if (maxOfferAttempts <= 0) {
            throw new IllegalArgumentException("maxOfferAttempts must be positive.");
        }
        this.ingressClient = ingressClient;
        this.idleStrategy = idleStrategy;
        this.maxOfferAttempts = maxOfferAttempts;
        this.metrics = metrics;
    }

    private static IngressClient connectCluster(List<String> hostnames, String ip) {
        if (hostnames == null || hostnames.isEmpty()) {
            throw new IllegalArgumentException("At least one cluster hostname is required.");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hostnames.size(); i++) {
            sb.append(i).append('=').append(hostnames.get(i)).append(':').append(9000 + ClusterMain.getClientFacingPortOffset()).append(',');
        }
        sb.setLength(sb.length() - 1);
        String ingressEndpoints = sb.toString();


        MediaDriver mediaDriver = MediaDriver.launchEmbedded(
            new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));

        try {
            AeronCluster aeronCluster = AeronCluster.connect(
                new AeronCluster.Context()
                    .messageTimeoutNs(TimeUnit.SECONDS.toNanos(10))
                    .egressListener((clusterSessionId, timestamp, buffer, offset, length, header)->{})
                    .ingressChannel("aeron:udp?endpoint="+hostnames.get(0)+":9002|term_length=64k")
                    .aeronDirectoryName(mediaDriver.aeronDirectoryName())
                    .egressChannel("aeron:udp?endpoint="+ip+":0|term_length=64k")
                    .ingressEndpoints(ingressEndpoints)
            );
            return new AeronClusterIngressClient(mediaDriver, aeronCluster);
        } catch (RuntimeException e) {
            mediaDriver.close();
            throw e;
        }
    }

    public void close() {
        ingressClient.close();
    }

    public synchronized boolean writeToCluster(byte[] message) {
        buf.putBytes(0, message);
        return offerBounded(message.length);
    }

    private boolean offerBounded(int length) {
        if (maxOfferAttempts > 1) {
            idleStrategy.reset();
        }
        for (int attempt = 0; attempt < maxOfferAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                metrics.increment(DROPPED_COUNTER);
                Thread.currentThread().interrupt();
                return false;
            }
            long result = ingressClient.offer(buf, 0, length);
            if (result >= 0L) {
                return true;
            }
            metrics.increment(OFFER_FAILED_COUNTER);
            if (Thread.currentThread().isInterrupted()) {
                metrics.increment(DROPPED_COUNTER);
                Thread.currentThread().interrupt();
                return false;
            }
            if (attempt + 1 < maxOfferAttempts) {
                idleStrategy.idle(ingressClient.pollEgress());
            }
        }
        metrics.increment(DROPPED_COUNTER);
        return false;
    }

    interface IngressClient {
        long offer(MutableDirectBuffer buffer, int offset, int length);

        int pollEgress();

        void close();
    }

    private static final class AeronClusterIngressClient implements IngressClient {
        private final MediaDriver mediaDriver;
        private final AeronCluster aeronCluster;

        private AeronClusterIngressClient(MediaDriver mediaDriver, AeronCluster aeronCluster) {
            this.mediaDriver = mediaDriver;
            this.aeronCluster = aeronCluster;
        }

        @Override
        public long offer(MutableDirectBuffer buffer, int offset, int length) {
            return aeronCluster.offer(buffer, offset, length);
        }

        @Override
        public int pollEgress() {
            return aeronCluster.pollEgress();
        }

        @Override
        public void close() {
            aeronCluster.close();
            mediaDriver.close();
        }
    }

}
