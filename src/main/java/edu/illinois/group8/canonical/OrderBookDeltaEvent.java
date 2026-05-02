package edu.illinois.group8.canonical;

public record OrderBookDeltaEvent(
    String eventId,
    String eventType,
    int schemaVersion,
    String streamName,
    EventMetadata metadata,
    String side,
    long priceMicros,
    long deltaQuantityMicros,
    String sourcePrice,
    String sourceDelta
) implements CanonicalEvent {
    public OrderBookDeltaEvent(
        String eventId,
        EventMetadata metadata,
        String side,
        long priceMicros,
        long deltaQuantityMicros,
        String sourcePrice,
        String sourceDelta
    ) {
        this(
            eventId,
            EventType.ORDER_BOOK_DELTA.eventType(),
            EventType.ORDER_BOOK_DELTA.schemaVersion(),
            EventType.ORDER_BOOK_DELTA.streamName(),
            metadata,
            side,
            priceMicros,
            deltaQuantityMicros,
            sourcePrice,
            sourceDelta
        );
    }
}
