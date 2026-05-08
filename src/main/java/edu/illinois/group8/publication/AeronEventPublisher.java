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
        long offerStartTsNs = System.nanoTime();
        byte[] bytes = serializer.toBytes(event);
        buffer.putBytes(0, bytes);
        var labels = BackendMetrics.labels("service", "backend", "stream", event.streamName());
        metrics.increment("backend_publication_offer_total", labels);
        long result = communicationOrchestrator.getInternalPublication().offer(buffer, 0, bytes.length);
        long offerEndTsNs = System.nanoTime();
        metrics.observe("backend_publication_latency_ns", labels, Math.max(0L, offerEndTsNs - offerStartTsNs));
        if (result < 0L) {
            metrics.increment("backend_publication_offer_failed_total", labels);
            metrics.observe("backend_publication_backpressure_ns", labels, Math.max(0L, offerEndTsNs - offerStartTsNs));
            metrics.increment("publication.offer_failed." + event.streamName());
            return false;
        }
        metrics.increment("publication.offer_success." + event.streamName());
        metrics.add("publication.bytes." + event.streamName(), bytes.length);
        return true;
    }
}
