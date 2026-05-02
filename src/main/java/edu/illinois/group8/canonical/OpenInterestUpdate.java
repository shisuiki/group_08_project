package edu.illinois.group8.canonical;

public record OpenInterestUpdate(
    String eventId,
    String eventType,
    int schemaVersion,
    String streamName,
    EventMetadata metadata,
    Long openInterestMicros,
    Long dollarOpenInterest
) implements CanonicalEvent {
    public OpenInterestUpdate(
        String eventId,
        EventMetadata metadata,
        Long openInterestMicros,
        Long dollarOpenInterest
    ) {
        this(
            eventId,
            EventType.OPEN_INTEREST_UPDATE.eventType(),
            EventType.OPEN_INTEREST_UPDATE.schemaVersion(),
            EventType.OPEN_INTEREST_UPDATE.streamName(),
            metadata,
            openInterestMicros,
            dollarOpenInterest
        );
    }
}
