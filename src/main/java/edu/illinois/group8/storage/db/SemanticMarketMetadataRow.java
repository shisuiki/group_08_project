package edu.illinois.group8.storage.db;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SemanticMarketMetadataRow(
    String marketTicker,
    String eventTicker,
    String seriesTicker,
    String marketStatus,
    String marketTitle,
    String taxonomyVersion,
    String model,
    String promptVersion,
    String semanticStatus,
    String sector,
    String subsector,
    String eventType,
    String region,
    String timeHorizon,
    String liquidityBucket,
    String riskBucket,
    List<String> tags,
    BigDecimal confidence,
    String rationale,
    Instant generatedAt,
    Instant updatedAt,
    Long generatedAgeMs,
    Long updatedAgeMs,
    Long lastEventTsMs,
    String lastCanonicalEventId,
    Long lastCanonicalCommitSeq,
    Long bestBidMicros,
    Long bestAskMicros,
    Long midpointMicros,
    Long openInterest,
    Instant latestStateUpdatedAt,
    Long latestStateAgeMs
) {
    public SemanticMarketMetadataRow {
        marketTicker = nonBlank(marketTicker, "marketTicker");
        taxonomyVersion = nonBlank(taxonomyVersion, "taxonomyVersion");
        semanticStatus = nonBlank(semanticStatus, "semanticStatus");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value.trim();
    }
}
