package edu.illinois.group8.cluster;

import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster.Role;
import io.aeron.ExclusivePublication;
import io.aeron.Image;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.agrona.DirectBuffer;
import org.yaml.snakeyaml.Yaml;

import io.aeron.logbuffer.Header;
import org.json.*;
import com.google.gson.Gson;

public class ESBClusteredService implements ClusteredService {
    private Cluster cluster;
    private Role currentRole = Role.FOLLOWER;

    private String extractMessageType(String message) {
        JSONObject obj = new JSONObject(message);
        return obj.getString("type");
    }
    
    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        // if (snapshotImage != null) {
        //     // TODO: write snapshot loader
        //     // will write snapshot loader later, based on what we need for data analysis like orderbook etc
        //     // if the cluster doesn't actually need to store anything locally we can just get away with no snapshots
        //     // however if we want to do inmemory tables we will need a snapshot system
        // }
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

        String message = buffer.getStringUtf8(offset, length);
        String messageType = extractMessageType(message);
        Gson gson = new Gson();
        
        // switch (messageType) {
        //     case "orderbook_snapshot":
        //         OrderBookSnapshot book = gson.fromJson(message, OrderBookSnapshot.class);
        //         // write function to process data and write to aeron channel
        //         break;
        // }
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Handle timer events
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        // Save the current state to a snapshot
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
