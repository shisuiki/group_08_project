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

public class ESBClusteredService implements ClusteredService {
    private Cluster cluster;
    private Map<String, String> routingRules;

    private void loadRoutingRules() {
        routingRules = new HashMap<>();
        Yaml yaml = new Yaml();

        InputStream inputStream = ESBClusteredService.class.getResourceAsStream("../../../../resources/config/routes.yaml");
        List<Map<String, String>> routes = yaml.load(inputStream);

        for (Map<String, String> route : routes) {
            routingRules.put(route.get("messageType"), route.get("destination"));
        }
    }
    
    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        loadRoutingRules();
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
    }

    @Override
    public void onTerminate(Cluster cluster) {
        // Clean up resources before termination
    }
}
