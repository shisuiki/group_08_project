package edu.illinois.group8.publication;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.metrics.BackendMetrics;
import org.agrona.ExpandableArrayBuffer;

import java.util.concurrent.ConcurrentHashMap;

public class AeronEventPublisher implements EventPublisher {
    static final int HOT_PATH_DISTRIBUTION_SAMPLE_MASK = 63;

    private final ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final JsonCanonicalSerializer serializer;
    private final BackendMetrics metrics;
    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();
    private final ConcurrentHashMap<PublicationMetricKey, PublicationMetricHandles> metricHandles = new ConcurrentHashMap<>();
    private long publicationDistributionSampleCursor;

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
        boolean sampleDistributions = shouldSamplePublicationDistribution();
        long offerStartTsNs = sampleDistributions ? System.nanoTime() : 0L;
        byte[] bytes = serializer.toBytes(event);
        buffer.putBytes(0, bytes);
        PublicationMetricHandles handles = metricHandles.computeIfAbsent(
            new PublicationMetricKey(event.streamName()),
            this::publicationMetricHandles
        );
        handles.offerTotal.increment();
        long result = communicationOrchestrator.getInternalPublication().offer(buffer, 0, bytes.length);
        long elapsedNs = sampleDistributions ? Math.max(0L, System.nanoTime() - offerStartTsNs) : 0L;
        return recordPublicationOutcome(handles, result, bytes.length, elapsedNs, sampleDistributions);
    }

    static boolean shouldSampleHotPathDistribution(long cursor) {
        return (cursor & HOT_PATH_DISTRIBUTION_SAMPLE_MASK) == 0L;
    }

    private boolean shouldSamplePublicationDistribution() {
        return shouldSampleHotPathDistribution(publicationDistributionSampleCursor++);
    }

    static boolean recordPublicationOutcome(
        PublicationMetricHandles handles,
        long result,
        int bytesLength,
        long elapsedNs,
        boolean sampleDistributions
    ) {
        if (sampleDistributions) {
            handles.latency.observe(Math.max(0L, elapsedNs));
        }
        if (result < 0L) {
            handles.offerFailed.increment();
            if (sampleDistributions) {
                handles.backpressure.observe(Math.max(0L, elapsedNs));
            }
            handles.legacyOfferFailed.increment();
            return false;
        }
        handles.legacyOfferSuccess.increment();
        handles.legacyBytes.add(bytesLength);
        return true;
    }

    private PublicationMetricHandles publicationMetricHandles(PublicationMetricKey key) {
        return publicationMetricHandles(metrics, key.streamName());
    }

    static PublicationMetricHandles publicationMetricHandles(BackendMetrics metrics, String streamName) {
        var labels = BackendMetrics.labels("service", "backend", "stream", streamName);
        return new PublicationMetricHandles(
            metrics.counter("backend_publication_offer_total", labels),
            metrics.distribution("backend_publication_latency_ns", labels),
            metrics.counter("backend_publication_offer_failed_total", labels),
            metrics.distribution("backend_publication_backpressure_ns", labels),
            metrics.counter("publication.offer_failed." + streamName),
            metrics.counter("publication.offer_success." + streamName),
            metrics.counter("publication.bytes." + streamName)
        );
    }

    private record PublicationMetricKey(String streamName) {
    }

    record PublicationMetricHandles(
        BackendMetrics.Counter offerTotal,
        BackendMetrics.DistributionHandle latency,
        BackendMetrics.Counter offerFailed,
        BackendMetrics.DistributionHandle backpressure,
        BackendMetrics.Counter legacyOfferFailed,
        BackendMetrics.Counter legacyOfferSuccess,
        BackendMetrics.Counter legacyBytes
    ) {
    }
}
