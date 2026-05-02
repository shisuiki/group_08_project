package edu.illinois.group8.canonical;

public record MarketTrade(
    String eventId,
    String eventType,
    int schemaVersion,
    String streamName,
    EventMetadata metadata,
    String tradeId,
    Long yesPriceMicros,
    Long noPriceMicros,
    Long quantityMicros,
    String takerSide
) implements CanonicalEvent {
    public MarketTrade(
        String eventId,
        EventMetadata metadata,
        String tradeId,
        Long yesPriceMicros,
        Long noPriceMicros,
        Long quantityMicros,
        String takerSide
    ) {
        this(
            eventId,
            EventType.MARKET_TRADE.eventType(),
            EventType.MARKET_TRADE.schemaVersion(),
            EventType.MARKET_TRADE.streamName(),
            metadata,
            tradeId,
            yesPriceMicros,
            noPriceMicros,
            quantityMicros,
            takerSide
        );
    }
}
