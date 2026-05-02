package edu.illinois.group8.canonical;

public record RawSourceEvent(
    String eventId,
    String eventType,
    int schemaVersion,
    String streamName,
    EventMetadata metadata,
    String rawPayload
) implements CanonicalEvent {
    public RawSourceEvent(String eventId, EventMetadata metadata, String rawPayload) {
        this(
            eventId,
            EventType.RAW_SOURCE_EVENT.eventType(),
            EventType.RAW_SOURCE_EVENT.schemaVersion(),
            EventType.RAW_SOURCE_EVENT.streamName(),
            metadata,
            rawPayload
        );
    }
}
