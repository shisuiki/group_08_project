package edu.illinois.group8.cluster;

import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster.Role;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Image;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import io.aeron.logbuffer.Header;
import org.json.*;

import edu.illinois.group8.esb.DataProcessor;
import edu.illinois.group8.tickerplant.TPAeronServer;
import edu.illinois.group8.demo.MarketGridDemo;

public class ESBClusteredService implements ClusteredService {
    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private Role currentRole = Role.FOLLOWER;
    private Aeron aeron;
    private String hostname;
    private String aeronDirName;
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private DataProcessor processor;
    private Thread tickerplantThread;
    private Thread clientThread;

    public ESBClusteredService(String aeronDirName, String hostname) {
        this.aeronDirName = aeronDirName;
        this.hostname = hostname;
    }
    
    private void loadSnapshot(final Cluster cluster, final Image snapshotImage) {

        if (snapshotImage == null) {
            // No snapshot to load; initialize default state
            // initializeDefaultState();
            return;
        }
    
        try {
            // Poll the snapshot image for data
            DirectBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(1024)); // Adjust size as needed
            int length = snapshotImage.poll((buf, offset, bufLength, header) -> {
                String snapshotData = buf.getStringUtf8(offset, bufLength);
                JSONObject obj = new JSONObject(snapshotData);
                // Since there's no state to restore, no action is needed
            }, buffer.capacity());
    
            if (length == 0) {
                // Empty snapshot received
                // initializeDefaultState();
            }
        } catch (Exception e) {
            e.printStackTrace();
            // initializeDefaultState();
        }
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        this.idleStrategy = cluster.idleStrategy();

        Aeron.Context ctx = new Aeron.Context().aeronDirectoryName(aeronDirName);
        aeron = Aeron.connect(ctx);

        this.communicationOrchestrator = new ESBClusterCommunicationOrchestrator(this.hostname, true, aeronDirName);
        this.processor = new DataProcessor(communicationOrchestrator);
        this.tickerplantThread = new Thread(new TPAeronServer(communicationOrchestrator));
        this.tickerplantThread.start();
        this.clientThread = new Thread(new MarketGridDemo(communicationOrchestrator));
        this.clientThread.start();

        // TODO: write snapshot loader
        // will write snapshot loader later, based on what we need for data analysis like orderbook etc
        // if the cluster doesn't actually need to store anything locally we can just get away with no snapshots
        // however if we want to do inmemory tables we will need a snapshot system
        loadSnapshot(cluster, snapshotImage);
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        // Handle new client session

    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        // Handle client session closure
    }

    /**
     * Function triggered when a message is sent to the ESB cluster. Only triggered by the leader node.
     * Message is read then processed, and finally published to the corresponding channels.
     */
    @Override
    public void onSessionMessage(
        ClientSession session,
        long timestamp,
        DirectBuffer buffer,
        int offset,
        int length,
        Header header) {
        
        
        // Process incoming messages from clients
        if (currentRole != Role.LEADER) {
            return;
        }
        
        byte[] messageBytes = new byte[length];
        buffer.getBytes(offset, messageBytes);
        String payload = new String(messageBytes, StandardCharsets.UTF_8);

        // System.out.println("ESB: received message "+payload);

        processor.processMessage(payload);
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Handle timer events
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        // Save the current state to a snapshot
        idleStrategy.reset();

        try {
            JSONObject snapshot = new JSONObject();
            String snapshotString = snapshot.toString();
            byte[] snapshotBytes = snapshotString.getBytes(StandardCharsets.UTF_8);

            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(snapshotBytes.length));
            buffer.putBytes(0, snapshotBytes);

            while (snapshotPublication.offer(buffer, 0, snapshotBytes.length) < 0L) {
                idleStrategy.idle();
            }

            System.out.println("created empty snapshot");
        } catch (Exception e) {
            return;
        }
    }

    @Override
    public void onRoleChange(Role newRole) {
        // React to role changes (LEADER, FOLLOWER, etc.)
        System.out.println("new role: " + newRole);
        this.currentRole = newRole;
        
        // if (newRole == Role.LEADER) {
        //     // streamIDMap.forEach((key, streamID) -> {
        //     //     Publication pub = aeron.addPublication("aeron:udp?endpoint=224.0.1.3:40456", streamID); // Stream ID can vary per topic
        //     //     publicationMap.put(key, pub);
        //     // });
        // } else {
        //     // publicationMap.values().forEach(Publication::close);
        //     // publicationMap.clear();
        // }
    }

    @Override
    public void onTerminate(Cluster cluster) {
        // Clean up resources before termination
    }
}
