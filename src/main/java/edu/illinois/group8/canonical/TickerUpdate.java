package edu.illinois.group8.canonical;

public record TickerUpdate(
    String eventId,
    String eventType,
    int schemaVersion,
    String streamName,
    EventMetadata metadata,
    Long priceMicros,
    Long yesBidMicros,
    Long yesAskMicros,
    Long yesBidQuantityMicros,
    Long yesAskQuantityMicros,
    Long lastTradeQuantityMicros,
    Long volumeMicros,
    Long dollarVolume
) implements CanonicalEvent {
    public TickerUpdate(
        String eventId,
        EventMetadata metadata,
        Long priceMicros,
        Long yesBidMicros,
        Long yesAskMicros,
        Long yesBidQuantityMicros,
        Long yesAskQuantityMicros,
        Long lastTradeQuantityMicros,
        Long volumeMicros,
        Long dollarVolume
    ) {
        this(
            eventId,
            EventType.TICKER_UPDATE.eventType(),
            EventType.TICKER_UPDATE.schemaVersion(),
            EventType.TICKER_UPDATE.streamName(),
            metadata,
            priceMicros,
            yesBidMicros,
            yesAskMicros,
            yesBidQuantityMicros,
            yesAskQuantityMicros,
            lastTradeQuantityMicros,
            volumeMicros,
            dollarVolume
        );
    }
}
