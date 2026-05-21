package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.replay.recording.RecordingEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
            return fromEvent(streamName, payload, event);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed canonical envelope for stream " + streamName, e);
        }
    }

    public static CanonicalEnvelope fromPayloadBytes(
        String streamName,
        byte[] payloadUtf8,
        int offset,
        int length,
        ObjectMapper mapper
    ) {
        return fromPayloadBytes(streamName, payloadUtf8, offset, length, null, mapper);
    }

    public static CanonicalEnvelope fromPayloadBytes(
        String streamName,
        byte[] payloadUtf8,
        int offset,
        int length,
        Long consumerReceiveTsNs,
        ObjectMapper mapper
    ) {
        try {
            JsonNode event = mapper.readTree(payloadUtf8, offset, length);
            String payload = new String(payloadUtf8, offset, length, StandardCharsets.UTF_8);
            CanonicalEnvelope envelope = fromEvent(streamName, payload, event);
            return consumerReceiveTsNs == null
                ? envelope
                : new CanonicalEnvelope(
                    envelope.streamName(),
                    envelope.payload(),
                    envelope.event(),
                    envelope.eventTsMs(),
                    consumerReceiveTsNs
                );
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed canonical envelope for stream " + streamName, e);
        }
    }

    public Long metadataIngestTsNs() {
        return optionalLong(event.path("metadata").path("ingest_ts_ns"));
    }

    public Long metadataPublishTsNs() {
        return optionalLong(event.path("metadata").path("publish_ts_ns"));
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

    private static CanonicalEnvelope fromEvent(String streamName, String payload, JsonNode event) {
        return new CanonicalEnvelope(
            streamNameFrom(streamName, event),
            payload,
            event,
            optionalLong(event.path("metadata").path("event_ts_ms")),
            optionalLong(event.path("recorder_metadata").path("consumer_receive_ts_ns"))
        );
    }

    private static Long optionalLong(JsonNode node) {
        return node.isNumber() ? node.asLong() : null;
    }
}
