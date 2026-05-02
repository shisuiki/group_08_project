package edu.illinois.group8.canonical;

public record ParserErrorEvent(
    String eventId,
    String eventType,
    int schemaVersion,
    String streamName,
    EventMetadata metadata,
    String errorCode,
    String errorMessage,
    String rawPayload
) implements CanonicalEvent {
    public ParserErrorEvent(
        String eventId,
        EventMetadata metadata,
        String errorCode,
        String errorMessage,
        String rawPayload
    ) {
        this(
            eventId,
            EventType.PARSER_ERROR.eventType(),
            EventType.PARSER_ERROR.schemaVersion(),
            EventType.PARSER_ERROR.streamName(),
            metadata,
            errorCode,
            errorMessage,
            rawPayload
        );
    }
}
