package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.replay.recording.RecordingEvent;

import java.io.IOException;

public record CanonicalEnvelope(
    String streamName,
    String payload,
    JsonNode event,
    Long eventTsMs,
    Long consumerReceiveTsNs
) {
    public static CanonicalEnvelope fromPayload(String streamName, String payload, ObjectMapper mapper) {
        try {
            JsonNode event = mapper.readTree(payload);
            return new CanonicalEnvelope(
                streamNameFrom(streamName, event),
                payload,
                event,
                optionalLong(event.path("metadata").path("event_ts_ms")),
                optionalLong(event.path("recorder_metadata").path("consumer_receive_ts_ns"))
            );
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed canonical envelope for stream " + streamName, e);
        }
    }

    public static CanonicalEnvelope fromRecording(RecordingEvent event, ObjectMapper mapper) {
        CanonicalEnvelope envelope = fromPayload(event.streamName(), event.payload(), mapper);
        return new CanonicalEnvelope(
            envelope.streamName(),
            envelope.payload(),
            envelope.event(),
            event.eventTsMs() == null ? envelope.eventTsMs() : event.eventTsMs(),
            envelope.consumerReceiveTsNs()
        );
    }

    public String eventId() {
        return event.path("event_id").asText("");
    }

    public String eventType() {
        return event.path("event_type").asText("unknown");
    }

    public String marketTicker() {
        return event.path("metadata").path("market_ticker").asText("");
    }

    private static String streamNameFrom(String fallback, JsonNode event) {
        String streamName = event.path("stream_name").asText("");
        return streamName.isBlank() ? fallback : streamName;
    }

    private static Long optionalLong(JsonNode node) {
        return node.isNumber() ? node.asLong() : null;
    }
}
