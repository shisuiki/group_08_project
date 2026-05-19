package edu.illinois.group8.storage.db;

public record LatestMarketState(
    String marketTicker,
    Long lastEventTsMs,
    String lastCanonicalEventId,
    Long bestBidMicros,
    Long bestAskMicros,
    Long midpointMicros,
    Long openInterest,
    String payload
) {
    public LatestMarketState {
        if (marketTicker == null || marketTicker.isBlank()) {
            throw new IllegalArgumentException("marketTicker must not be blank");
        }
    }
}
