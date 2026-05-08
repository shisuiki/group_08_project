package edu.illinois.group8.cluster;

import java.util.List;
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
    private final MediaDriver mediaDriver;
    private final AeronCluster aeronCluster;
    private final IdleStrategy idleStrategy = new BackoffIdleStrategy();
    private final MutableDirectBuffer buf = new ExpandableArrayBuffer();

    public ClientClusterOrchestrator(List<String> hostnames, String ip) {
        if (hostnames == null || hostnames.isEmpty()) {
            throw new IllegalArgumentException("At least one cluster hostname is required.");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hostnames.size(); i++) {
            sb.append(i).append('=').append(hostnames.get(i)).append(':').append(9000 + ClusterMain.getClientFacingPortOffset()).append(',');
        }
        sb.setLength(sb.length() - 1);
        String ingressEndpoints = sb.toString();


        mediaDriver = MediaDriver.launchEmbedded(
            new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));

        aeronCluster = AeronCluster.connect(
            new AeronCluster.Context()
                .messageTimeoutNs(TimeUnit.SECONDS.toNanos(10))
                .egressListener((clusterSessionId, timestamp, buffer, offset, length, header)->{})
                .ingressChannel("aeron:udp?endpoint="+hostnames.get(0)+":9002|term_length=64k")
                .aeronDirectoryName(mediaDriver.aeronDirectoryName())
                .egressChannel("aeron:udp?endpoint="+ip+":0|term_length=64k")
                .ingressEndpoints(ingressEndpoints)
        );
    }

    public void close() {
        aeronCluster.close();
        mediaDriver.close();
    }

    public synchronized boolean writeToCluster(byte[] message) {
        buf.putBytes(0, message);
        idleStrategy.reset();
        while (aeronCluster.offer(buf, 0, message.length) < 0) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            idleStrategy.idle(aeronCluster.pollEgress());
        }
        return true;
    }

    public synchronized boolean writeToCluster(String message) {
        buf.putBytes(0, message.getBytes());
        idleStrategy.reset();
        while (aeronCluster.offer(buf, 0, message.length()) < 0) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            idleStrategy.idle(aeronCluster.pollEgress());
        }
        return true;
    }

}
