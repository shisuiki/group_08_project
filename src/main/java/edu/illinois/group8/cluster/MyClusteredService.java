package edu.illinois.group8.cluster;

import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.CloseReason;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.logbuffer.Header;
import io.aeron.ExclusivePublication;
import org.agrona.DirectBuffer;

public class MyClusteredService implements ClusteredService {
    
    @Override
    public void onStart(ClusteredServiceContainer container) {
        System.out.println("Clustered service started.");
        // Initialize your service state here
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        System.out.println("Session opened: " + session.id());
        // Handle new client sessions
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {
        System.out.println("Session closed: " + session.id() + " Reason: " + reason);
        // Handle session closures
    }

    @Override
    public void onSessionMessage(
            ClientSession session,
            long timestamp,
            DirectBuffer buffer,
            int offset,
            int length,
            Header header) {
        String message = buffer.getStringWithoutLengthUtf8(offset, length);
        System.out.println("Received message from session " + session.id() + ": " + message);
        // Process the received message and implement your business logic
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        System.out.println("Timer event: " + correlationId);
        // Handle timer events
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication publication) {
        System.out.println("Taking snapshot.");
        // Serialize and persist the current state for snapshotting
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        System.out.println("Role changed to: " + newRole);
        // Handle role changes (e.g., Leader, Follower)
    }

    @Override
    public void onTerminate(Cluster.TerminationStatus status) {
        System.out.println("Service terminating with status: " + status);
        // Cleanup resources before termination
    }
}
