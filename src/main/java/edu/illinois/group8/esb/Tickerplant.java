package edu.illinois.group8.esb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.metrics.BackendMetrics;
import io.aeron.Publication;
import io.aeron.Subscription;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public class Tickerplant implements Runnable {
    private final ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final Subscription internalSubscription;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BackendMetrics metrics;
    private final BackendMetrics.Counter missingStreamNameFailures;
    private final BackendMetrics.Counter parseFailures;
    private final ConcurrentHashMap<String, RouteMetricHandles> routeMetricHandles = new ConcurrentHashMap<>();

    public Tickerplant(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this(communicationOrchestrator, new BackendMetrics());
    }

    public Tickerplant(ESBClusterCommunicationOrchestrator communicationOrchestrator, BackendMetrics metrics) {
        this.communicationOrchestrator = communicationOrchestrator;
        this.internalSubscription = communicationOrchestrator.getInternalSubscription();
        this.metrics = metrics;
        this.missingStreamNameFailures = metrics.counter("tickerplant.route_failed.missing_stream_name");
        this.parseFailures = metrics.counter("tickerplant.route_failed.parse_error");
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
                missingStreamNameFailures.increment();
                return false;
            }
            Publication publication = communicationOrchestrator.getPublication(streamName);
            if (publication == null) {
                metrics.increment("tickerplant.route_failed.unknown_stream." + streamName);
                return false;
            }
            RouteMetricHandles handles = routeMetricHandles.computeIfAbsent(streamName, this::routeMetricHandles);
            long offerStartTsNs = System.nanoTime();
            handles.offerTotal.increment();
            long result = publication.offer(buffer, offset, length);
            long offerEndTsNs = System.nanoTime();
            handles.latency.observe(Math.max(0L, offerEndTsNs - offerStartTsNs));
            if (result < 0L) {
                handles.offerFailed.increment();
                handles.backpressure.observe(Math.max(0L, offerEndTsNs - offerStartTsNs));
                handles.routeOfferFailed.increment();
                return false;
            }
            handles.routeSuccess.increment();
            return true;
        } catch (Exception e) {
            parseFailures.increment();
            return false;
        }
    }

    public BackendMetrics metrics() {
        return metrics;
    }

    private RouteMetricHandles routeMetricHandles(String streamName) {
        return routeMetricHandles(metrics, streamName);
    }

    static RouteMetricHandles routeMetricHandles(BackendMetrics metrics, String streamName) {
        var labels = BackendMetrics.labels("service", "tickerplant", "stream", streamName);
        return new RouteMetricHandles(
            metrics.counter("backend_publication_offer_total", labels),
            metrics.distribution("backend_publication_latency_ns", labels),
            metrics.counter("backend_publication_offer_failed_total", labels),
            metrics.distribution("backend_publication_backpressure_ns", labels),
            metrics.counter("tickerplant.route_failed.offer." + streamName),
            metrics.counter("tickerplant.route_success." + streamName)
        );
    }

    record RouteMetricHandles(
        BackendMetrics.Counter offerTotal,
        BackendMetrics.DistributionHandle latency,
        BackendMetrics.Counter offerFailed,
        BackendMetrics.DistributionHandle backpressure,
        BackendMetrics.Counter routeOfferFailed,
        BackendMetrics.Counter routeSuccess
    ) {
    }
}
