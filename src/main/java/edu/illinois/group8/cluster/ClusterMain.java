package edu.illinois.group8.cluster;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;

import io.aeron.ChannelUriStringBuilder;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;

public class ClusterMain {
    // private static generate
    private static final int TERM_LENGTH = 64 * 1024;
    private static final int ARCHIVE_CONTROL_PORT_OFFSET = 1;
    static final int CLIENT_FACING_PORT_OFFSET = 2;
    private static final int MEMBER_FACING_PORT_OFFSET = 3;
    private static final int LOG_PORT_OFFSET = 4;
    private static final int TRANSFER_PORT_OFFSET = 5;
    private static final int LOG_CONTROL_PORT_OFFSET = 6;

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

    public static void main(String[] args) {
        String clusterAddressesEnv = System.getenv("CLUSTER_ADDRESSES");
        String clusterNodeEnv = System.getenv("CLUSTER_NODE");
        String baseDirEnv = System.getenv("BASE_DIR");
        String clusterPortBaseEnv = System.getenv("CLUSTER_PORT_BASE");
        
        if (clusterAddressesEnv == null || clusterNodeEnv == null || baseDirEnv == null || clusterPortBaseEnv == null) {
            System.err.println("Missing required environment variables. Please set CLUSTER_ADDRESSES, CLUSTER_NODE, BASE_DIR, and CLUSTER_PORT_BASE.");
            System.exit(1);
        }

        List<String> clusterAddresses = Arrays.asList(clusterAddressesEnv.split(","));
        int nodeID = Integer.parseInt(clusterNodeEnv);
        String baseDir = baseDirEnv;
        int CLUSTER_PORT_BASE = Integer.parseInt(clusterPortBaseEnv);

        ClusteredService clusteredService = new ESBClusteredService();
        
        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        final String aeronDirName = baseDir + "/aeron-" + nodeID;

        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirName)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .terminationHook(barrier::signal);

        String archiveDirName = baseDir + "/archive-" + nodeID;
        Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDirName)
            .archiveDir(new File(archiveDirName))
            .controlChannel(createUDPChannel(CLUSTER_PORT_BASE, "localhost", ARCHIVE_CONTROL_PORT_OFFSET)) // TODO: change this hostname to one in clusterAddresses
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(true);

        AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
            .lock(NoOpLock.INSTANCE)
            .aeronDirectoryName(aeronDirName)
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlResponseChannel(archiveContext.localControlChannel());

        // String clusterMembers = buildClusterMembers(clusterAddresses, clusterPortBase);

        // TODO: add consensus module
        // TODO: add clustered service module
        
    }
}
