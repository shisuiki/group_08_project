package edu.illinois.group8.canonical;

import com.fasterxml.jackson.databind.JsonNode;

public record MarketLifecycleUpdate(
    String eventId,
    String eventType,
    int schemaVersion,
    String streamName,
    EventMetadata metadata,
    String lifecycleEventType,
    Long openTs,
    Long closeTs,
    Boolean fractionalTradingEnabled,
    String priceLevelStructure,
    JsonNode additionalMetadata
) implements CanonicalEvent {
    public MarketLifecycleUpdate(
        String eventId,
        EventMetadata metadata,
        String lifecycleEventType,
        Long openTs,
        Long closeTs,
        Boolean fractionalTradingEnabled,
        String priceLevelStructure,
        JsonNode additionalMetadata
    ) {
        this(
            eventId,
            EventType.MARKET_LIFECYCLE_UPDATE.eventType(),
            EventType.MARKET_LIFECYCLE_UPDATE.schemaVersion(),
            EventType.MARKET_LIFECYCLE_UPDATE.streamName(),
            metadata,
            lifecycleEventType,
            openTs,
            closeTs,
            fractionalTradingEnabled,
            priceLevelStructure,
            additionalMetadata
        );
    }
}
