package edu.illinois.group8.publication;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.metrics.BackendMetrics;
import org.agrona.ExpandableArrayBuffer;

public class AeronEventPublisher implements EventPublisher {
    private final ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final JsonCanonicalSerializer serializer;
    private final BackendMetrics metrics;
    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();

    public AeronEventPublisher(
        ESBClusterCommunicationOrchestrator communicationOrchestrator,
        JsonCanonicalSerializer serializer,
        BackendMetrics metrics
    ) {
        this.communicationOrchestrator = communicationOrchestrator;
        this.serializer = serializer;
        this.metrics = metrics;
    }

    @Override
    public boolean publish(CanonicalEvent event) {
        byte[] bytes = serializer.toBytes(event);
        buffer.putBytes(0, bytes);
        long result = communicationOrchestrator.getInternalPublication().offer(buffer, 0, bytes.length);
        if (result < 0L) {
            metrics.increment("publication.offer_failed." + event.streamName());
            return false;
        }
        metrics.increment("publication.offer_success." + event.streamName());
        metrics.add("publication.bytes." + event.streamName(), bytes.length);
        return true;
    }
}
