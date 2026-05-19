package edu.illinois.group8.storage.db;

import java.time.Instant;
import java.util.Objects;

public record MarketMetadata(
    String marketTicker,
    String eventTicker,
    String seriesTicker,
    String status,
    Instant openTime,
    Instant closeTime,
    Instant settlementTime,
    String rulesPayload,
    String marketPayload
) {
    public MarketMetadata {
        if (marketTicker == null || marketTicker.isBlank()) {
            throw new IllegalArgumentException("marketTicker must not be blank");
        }
        Objects.requireNonNull(marketPayload, "marketPayload");
    }
}
