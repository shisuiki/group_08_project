package edu.illinois.group8.esb;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.publication.CanonicalRouteEnvelope;
import io.aeron.Publication;
import io.aeron.Subscription;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public class Tickerplant implements Runnable {
    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    static final int HOT_PATH_DISTRIBUTION_SAMPLE_MASK = 63;

    private final ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final Subscription internalSubscription;
    private final BackendMetrics metrics;
    private final BackendMetrics.Counter missingStreamNameFailures;
    private final BackendMetrics.Counter parseFailures;
    private final ConcurrentHashMap<String, RouteMetricHandles> routeMetricHandles = new ConcurrentHashMap<>();
    private long routeDistributionSampleCursor;

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
                routePayload(data, 0, length, buffer, offset, length);
            }, 10);
            if (fragments == 0) {
                Thread.onSpinWait();
            }
        }
    }

    public boolean routeMessage(String message, org.agrona.DirectBuffer buffer, int offset, int length) {
        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            return routePayload(data, 0, data.length, buffer, offset, length);
        } catch (Exception e) {
            parseFailures.increment();
            return false;
        }
    }

    boolean routePayload(byte[] data, int dataOffset, int dataLength, org.agrona.DirectBuffer buffer, int offset, int length) {
        try {
            RoutePayload route = resolveRoute(data, dataOffset, dataLength);
            String streamName = route.streamName();
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
            boolean sampleDistributions = shouldSampleRouteDistribution();
            long offerStartTsNs = sampleDistributions ? System.nanoTime() : 0L;
            handles.offerTotal.increment();
            int publishOffset = offset;
            int publishLength = length;
            if (route.headerRouted()) {
                publishOffset = offset + (route.payloadOffset() - dataOffset);
                publishLength = route.payloadLength();
            }
            long result = publication.offer(buffer, publishOffset, publishLength);
            long elapsedNs = sampleDistributions ? Math.max(0L, System.nanoTime() - offerStartTsNs) : 0L;
            return recordRouteOutcome(handles, result, elapsedNs, sampleDistributions);
        } catch (Exception e) {
            parseFailures.increment();
            return false;
        }
    }

    static RoutePayload resolveRoute(byte[] data, int offset, int length) throws IOException {
        CanonicalRouteEnvelope.ParseResult route = CanonicalRouteEnvelope.parse(data, offset, length);
        if (route.malformed()) {
            throw new IOException("Malformed canonical route envelope");
        }
        if (route.headerPresent()) {
            return new RoutePayload(
                route.streamName(),
                route.payloadOffset(),
                route.payloadLength(),
                true
            );
        }
        return new RoutePayload(
            extractTopLevelStreamName(data, offset, length),
            offset,
            length,
            false
        );
    }

    static String extractTopLevelStreamName(String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        return extractTopLevelStreamName(data, 0, data.length);
    }

    static String extractTopLevelStreamName(byte[] data, int offset, int length) throws IOException {
        try (JsonParser parser = JSON_FACTORY.createParser(data, offset, length)) {
            return extractTopLevelStreamName(parser);
        }
    }

    private static String extractTopLevelStreamName(JsonParser parser) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == null) {
            return null;
        }
        String streamName = null;
        if (token == JsonToken.START_OBJECT) {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IOException("Malformed JSON object while reading stream_name");
                }
                String fieldName = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("stream_name".equals(fieldName)) {
                    streamName = scalarText(valueToken, parser);
                }
                parser.skipChildren();
            }
        } else {
            parser.skipChildren();
        }
        if (parser.nextToken() != null) {
            throw new IOException("Trailing JSON content after canonical event");
        }
        return streamName;
    }

    private static String scalarText(JsonToken token, JsonParser parser) throws IOException {
        if (token == null || token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token.isScalarValue()) {
            return parser.getText();
        }
        return "";
    }

    public BackendMetrics metrics() {
        return metrics;
    }

    static boolean shouldSampleHotPathDistribution(long cursor) {
        return (cursor & HOT_PATH_DISTRIBUTION_SAMPLE_MASK) == 0L;
    }

    private boolean shouldSampleRouteDistribution() {
        return shouldSampleHotPathDistribution(routeDistributionSampleCursor++);
    }

    static boolean recordRouteOutcome(
        RouteMetricHandles handles,
        long result,
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
            handles.routeOfferFailed.increment();
            return false;
        }
        handles.routeSuccess.increment();
        return true;
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

    record RoutePayload(
        String streamName,
        int payloadOffset,
        int payloadLength,
        boolean headerRouted
    ) {
    }
}
