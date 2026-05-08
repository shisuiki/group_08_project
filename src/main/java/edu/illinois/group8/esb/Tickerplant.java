package edu.illinois.group8.esb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.metrics.BackendMetrics;
import io.aeron.Publication;
import io.aeron.Subscription;

import java.nio.charset.StandardCharsets;

public class Tickerplant implements Runnable {
    private final ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final Subscription internalSubscription;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BackendMetrics metrics;

    public Tickerplant(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this(communicationOrchestrator, new BackendMetrics());
    }

    public Tickerplant(ESBClusterCommunicationOrchestrator communicationOrchestrator, BackendMetrics metrics) {
        this.communicationOrchestrator = communicationOrchestrator;
        this.internalSubscription = communicationOrchestrator.getInternalSubscription();
        this.metrics = metrics;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            int fragments = internalSubscription.poll((buffer, offset, length, header) -> {
                byte[] data = new byte[length];
                buffer.getBytes(offset, data);
                String message = new String(data, StandardCharsets.UTF_8);
                routeMessage(message, buffer, offset, length);
            }, 10);
            if (fragments == 0) {
                Thread.onSpinWait();
            }
        }
    }

    public boolean routeMessage(String message, org.agrona.DirectBuffer buffer, int offset, int length) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String streamName = root.path("stream_name").asText(null);
            if (streamName == null || streamName.isBlank()) {
                metrics.increment("tickerplant.route_failed.missing_stream_name");
                return false;
            }
            Publication publication = communicationOrchestrator.getPublication(streamName);
            if (publication == null) {
                metrics.increment("tickerplant.route_failed.unknown_stream." + streamName);
                return false;
            }
            var labels = BackendMetrics.labels("service", "tickerplant", "stream", streamName);
            long offerStartTsNs = System.nanoTime();
            metrics.increment("backend_publication_offer_total", labels);
            long result = publication.offer(buffer, offset, length);
            long offerEndTsNs = System.nanoTime();
            metrics.observe("backend_publication_latency_ns", labels, Math.max(0L, offerEndTsNs - offerStartTsNs));
            if (result < 0L) {
                metrics.increment("backend_publication_offer_failed_total", labels);
                metrics.observe("backend_publication_backpressure_ns", labels, Math.max(0L, offerEndTsNs - offerStartTsNs));
                metrics.increment("tickerplant.route_failed.offer." + streamName);
                return false;
            }
            metrics.increment("tickerplant.route_success." + streamName);
            return true;
        } catch (Exception e) {
            metrics.increment("tickerplant.route_failed.parse_error");
            return false;
        }
    }

    public BackendMetrics metrics() {
        return metrics;
    }
}
