package edu.illinois.group8.cluster;

import io.aeron.ChannelUriStringBuilder;
import io.aeron.CommonContext;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;

import org.agrona.ErrorHandler;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class ClusterMain {
    // private static generate
    private static final int TERM_LENGTH = 64 * 1024;
    private static final int ARCHIVE_CONTROL_PORT_OFFSET = 1;
    static final int CLIENT_FACING_PORT_OFFSET = 2;
    private static final int MEMBER_FACING_PORT_OFFSET = 3;
    private static final int LOG_PORT_OFFSET = 4;
    private static final int TRANSFER_PORT_OFFSET = 5;
    private static final int LOG_CONTROL_PORT_OFFSET = 6;

    public static int getClientFacingPortOffset() {
        return CLIENT_FACING_PORT_OFFSET;
    }

    public static int calculatePort(int nodePortBase, int portOffset) {
        return nodePortBase + portOffset;
    }

    private static String createUDPChannel(int nodePortBase, String hostname, int portOffset) {
        int port = calculatePort(nodePortBase, portOffset);
        return new ChannelUriStringBuilder()
            .media("udp")
            .termLength(TERM_LENGTH)
            .endpoint(hostname+":"+port)
            .build();
    }

    private static String logReplicationChannel(final int nodePortBase, final String hostname) {
        return createUDPChannel(nodePortBase, hostname, LOG_PORT_OFFSET);
    }

    // private static String createClusterMembers(final List<String> hostnames, int nodePortBase) {
    //     final StringBuilder sb = new StringBuilder();
    //     for (int i = 0; i < hostnames.size(); i++)
    //     {
    //         sb.append(i);
    //         sb.append(',').append(hostnames.get(i)).append(':').append(calculatePort(nodePortBase, CLIENT_FACING_PORT_OFFSET));
    //         sb.append(',').append(hostnames.get(i)).append(':').append(calculatePort(nodePortBase, MEMBER_FACING_PORT_OFFSET));
    //         sb.append(',').append(hostnames.get(i)).append(':').append(calculatePort(nodePortBase, LOG_PORT_OFFSET));
    //         sb.append(',').append(hostnames.get(i)).append(':').append(calculatePort(nodePortBase, TRANSFER_PORT_OFFSET));
    //         sb.append(',').append(hostnames.get(i)).append(':').append(calculatePort(nodePortBase, ARCHIVE_CONTROL_PORT_OFFSET));
    //         sb.append('|');
    //     }
    //     return sb.toString();
    // }

    private static String createClusterMembers(final List<String> hostnames, int nodePortBase) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hostnames.size(); i++) {
            sb.append(i);
            sb.append(',').append(hostnames.get(i)).append(':').append(calculatePort(nodePortBase, CLIENT_FACING_PORT_OFFSET))
              .append(',').append(hostnames.get(i)).append(':').append(calculatePort(nodePortBase, MEMBER_FACING_PORT_OFFSET))
              .append(',').append(hostnames.get(i)).append(':').append(calculatePort(nodePortBase, LOG_PORT_OFFSET))
              .append(',').append(hostnames.get(i)).append(':').append(calculatePort(nodePortBase, TRANSFER_PORT_OFFSET))
              .append(',').append(hostnames.get(i)).append(':').append(calculatePort(nodePortBase, ARCHIVE_CONTROL_PORT_OFFSET))
              .append('|');
        }
        return sb.toString();
    }
    

    private static ErrorHandler errorHandler(final String context)
    {
        return
            (Throwable throwable) ->
            {
                System.err.println(context);
                throwable.printStackTrace(System.err);
            };
    }

    public static void main(String[] args) {
        String clusterAddressesEnv = System.getenv("CLUSTER_ADDRESSES"); // gets IPs
        String clusterNodeEnv = System.getenv("NODE_ID"); // gets node id
        String baseDirEnv = System.getenv("BASE_DIR"); // gets base directory
        String clusterPortBaseEnv = System.getenv("CLUSTER_PORT_BASE"); // gets port base
        
        if (clusterAddressesEnv == null || clusterNodeEnv == null || baseDirEnv == null || clusterPortBaseEnv == null) {
            System.err.println("Missing required environment variables. Please set CLUSTER_ADDRESSES, CLUSTER_NODE, BASE_DIR, and CLUSTER_PORT_BASE.");
            System.exit(1);
        }

        List<String> clusterAddresses = Arrays.asList(clusterAddressesEnv.split(",")); // e.g. [127.0.0.2, 127.0.0.3, ...]
        
        int nodeID = Integer.parseInt(clusterNodeEnv);
        final String hostname = clusterAddresses.get(nodeID);
        String baseDir = baseDirEnv;
        int node_port_base = Integer.parseInt(clusterPortBaseEnv);
        
        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-" + nodeID + "-driver";

        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirName)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
            .terminationHook(barrier::signal)
            .dirDeleteOnStart(true)
            .errorHandler(ClusterMain.errorHandler("Media Driver"));

        final AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
            .controlResponseChannel("aeron:udp?endpoint=" + hostname + ":0");

        Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDirName)
            .archiveDir(new File(baseDir, "archive"))
            .controlChannel(createUDPChannel(node_port_base, hostname, ARCHIVE_CONTROL_PORT_OFFSET))
            .archiveClientContext(replicationArchiveContext)
            .localControlChannel("aeron:ipc?term-length=64k")
            .recordingEventsEnabled(false)
            .threadingMode(ArchiveThreadingMode.SHARED)
            .replicationChannel("aeron:udp?endpoint=" + hostname + ":0");

        AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
            .lock(NoOpLock.INSTANCE)
            .aeronDirectoryName(aeronDirName)
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlResponseChannel(archiveContext.localControlChannel());

        final ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
            .clusterMemberId(nodeID)
            .clusterDir(new File(baseDir, "cluster"))
            .ingressChannel("aeron:udp?endpoint="+hostname+":"+(node_port_base+CLIENT_FACING_PORT_OFFSET)+"|term_length=64k")
            .replicationChannel(logReplicationChannel(node_port_base, hostname))
            .clusterMembers(createClusterMembers(clusterAddresses, node_port_base))
            .archiveContext(aeronArchiveContext.clone())
            .errorHandler(errorHandler("Consensus Module"));

        final ClusteredServiceContainer.Context clusteredServiceContext = new ClusteredServiceContainer.Context()
            .aeronDirectoryName(aeronDirName)
            .archiveContext(aeronArchiveContext.clone())
            .clusterDir(new File(baseDir, "cluster"))
            .clusteredService(new ESBClusteredService(aeronDirName, hostname))
            .errorHandler(errorHandler("Clustered Service"));;
        
        try (
            ClusteredMediaDriver clusteredMediaDriver = ClusteredMediaDriver.launch(
                mediaDriverContext, archiveContext, consensusModuleContext);
            ClusteredServiceContainer container = ClusteredServiceContainer.launch(
                clusteredServiceContext))                 
        {
            System.out.println("[" + nodeID + "] Started Cluster Node on " + hostname + "...");
            barrier.await();
            System.out.println("[" + nodeID + "] Exiting");
        }

    }
}
