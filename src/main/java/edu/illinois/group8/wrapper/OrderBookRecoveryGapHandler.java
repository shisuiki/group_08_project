package edu.illinois.group8.wrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.SequenceGapEvent;

import java.util.Objects;

public final class OrderBookRecoveryGapHandler {
    private final OrderBookRecoveryController controller;
    private final ObjectMapper mapper;

    public OrderBookRecoveryGapHandler(OrderBookRecoveryController controller) {
        this(controller, new JsonCanonicalSerializer().mapper());
    }

    OrderBookRecoveryGapHandler(OrderBookRecoveryController controller, ObjectMapper mapper) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public Result handlePayload(String streamName, String payload) {
        if (!EventType.SEQUENCE_GAP.streamName().equals(streamName)) {
            return new Result(Status.SKIPPED_NON_GAP_STREAM, null);
        }
        if (payload == null || payload.isBlank()) {
            return new Result(Status.SKIPPED_MALFORMED_PAYLOAD, null);
        }

        try {
            JsonNode node = mapper.readTree(payload);
            if (!EventType.SEQUENCE_GAP.eventType().equals(node.path("event_type").asText())) {
                return new Result(Status.SKIPPED_NON_GAP_EVENT, null);
            }
            SequenceGapEvent gap = mapper.treeToValue(node, SequenceGapEvent.class);
            return new Result(Status.HANDLED, controller.handleGap(gap));
        } catch (Exception exc) {
            return new Result(Status.SKIPPED_MALFORMED_PAYLOAD, null);
        }
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
