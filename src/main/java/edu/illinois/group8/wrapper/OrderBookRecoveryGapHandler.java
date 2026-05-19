package edu.illinois.group8.wrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.metrics.BackendMetrics;

import java.util.Objects;

public final class OrderBookRecoveryGapHandler {
    private final OrderBookRecoveryController controller;
    private final ObjectMapper mapper;
    private final OrderBookRecoveryMetrics metrics;

    public OrderBookRecoveryGapHandler(OrderBookRecoveryController controller) {
        this(controller, new OrderBookRecoveryMetrics(new BackendMetrics()));
    }

    public OrderBookRecoveryGapHandler(OrderBookRecoveryController controller, OrderBookRecoveryMetrics metrics) {
        this(controller, new JsonCanonicalSerializer().mapper(), metrics);
    }

    OrderBookRecoveryGapHandler(OrderBookRecoveryController controller, ObjectMapper mapper) {
        this(controller, mapper, new OrderBookRecoveryMetrics(new BackendMetrics()));
    }

    OrderBookRecoveryGapHandler(
        OrderBookRecoveryController controller,
        ObjectMapper mapper,
        OrderBookRecoveryMetrics metrics
    ) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public Result handlePayload(String streamName, String payload) {
        if (!EventType.SEQUENCE_GAP.streamName().equals(streamName)) {
            return result(Status.SKIPPED_NON_GAP_STREAM, null);
        }
        if (payload == null || payload.isBlank()) {
            return result(Status.SKIPPED_MALFORMED_PAYLOAD, null);
        }

        try {
            JsonNode node = mapper.readTree(payload);
            if (!EventType.SEQUENCE_GAP.eventType().equals(node.path("event_type").asText())) {
                return result(Status.SKIPPED_NON_GAP_EVENT, null);
            }
            SequenceGapEvent gap = mapper.treeToValue(node, SequenceGapEvent.class);
            return result(Status.HANDLED, controller.handleGap(gap));
        } catch (Exception exc) {
            return result(Status.SKIPPED_MALFORMED_PAYLOAD, null);
        }
    }

    private Result result(Status status, OrderBookRecoveryController.RequestStatus requestStatus) {
        metrics.recordGapPayloadStatus(status);
        return new Result(status, requestStatus);
    }

    public enum Status {
        HANDLED,
        SKIPPED_NON_GAP_STREAM,
        SKIPPED_NON_GAP_EVENT,
        SKIPPED_MALFORMED_PAYLOAD
    }

    public record Result(Status status, OrderBookRecoveryController.RequestStatus requestStatus) {
    }
}
