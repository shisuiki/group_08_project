package edu.illinois.group8.cluster;

import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster.Role;
import io.aeron.ExclusivePublication;
import io.aeron.Image;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import io.aeron.logbuffer.Header;
import org.json.*;

public class ESBClusteredService implements ClusteredService {
    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private Role currentRole = Role.FOLLOWER;

    // private int SNAPSHOT_MESSAGE_LENGTH = 0;s

    private String extractMessageType(String message) {
        JSONObject obj = new JSONObject(message);
        return obj.getString("type");
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
        //     // TODO: write snapshot loader
        //     // will write snapshot loader later, based on what we need for data analysis like orderbook etc
        //     // if the cluster doesn't actually need to store anything locally we can just get away with no snapshots
        //     // however if we want to do inmemory tables we will need a snapshot system
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

        // String message = buffer.getStringUtf8(offset, length);
        // String messageType = extractMessageType(message);
        // Gson gson = new Gson();
        
        // // switch (messageType) {
        // //     case "orderbook_snapshot":
        // //         OrderBookSnapshot book = gson.fromJson(message, OrderBookSnapshot.class);
        // //         // write function to process data and write to aeron channel
        // //         break;
        // // }

        
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
        this.currentRole = newRole;
    }

    @Override
    public void onTerminate(Cluster cluster) {
        // Clean up resources before termination
    }
}
