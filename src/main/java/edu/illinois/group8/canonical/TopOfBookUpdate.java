package edu.illinois.group8.canonical;

public record TopOfBookUpdate(
    String eventId,
    String eventType,
    int schemaVersion,
    String streamName,
    EventMetadata metadata,
    long bidPriceMicros,
    long bidQuantityMicros,
    long askPriceMicros,
    long askQuantityMicros,
    boolean crossed
) implements CanonicalEvent {
    public TopOfBookUpdate(
        String eventId,
        EventMetadata metadata,
        long bidPriceMicros,
        long bidQuantityMicros,
        long askPriceMicros,
        long askQuantityMicros,
        boolean crossed
    ) {
        this(
            eventId,
            EventType.TOP_OF_BOOK_UPDATE.eventType(),
            EventType.TOP_OF_BOOK_UPDATE.schemaVersion(),
            EventType.TOP_OF_BOOK_UPDATE.streamName(),
            metadata,
            bidPriceMicros,
            bidQuantityMicros,
            askPriceMicros,
            askQuantityMicros,
            crossed
        );
    }
}
