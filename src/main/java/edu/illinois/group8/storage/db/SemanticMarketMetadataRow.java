package edu.illinois.group8.storage.db;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SemanticMarketMetadataRow(
    String marketTicker,
    String baseMarketKey,
    String sideTag,
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
    Long aggregateOpenInterest,
    Long currentMidpointMicros,
    Long midpoint24hAgoMicros,
    Long priceChange24hMicros,
    Instant latestStateUpdatedAt,
    Long latestStateAgeMs
) {
    public SemanticMarketMetadataRow {
        marketTicker = nonBlank(marketTicker, "marketTicker");
        baseMarketKey = nonBlank(baseMarketKey == null ? defaultBaseMarketKey(marketTicker) : baseMarketKey, "baseMarketKey");
        sideTag = normalize(sideTag == null ? defaultSideTag(marketTicker) : sideTag);
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

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String defaultBaseMarketKey(String marketTicker) {
        int index = marketTicker.lastIndexOf('-');
        return index <= 0 ? marketTicker : marketTicker.substring(0, index);
    }

    private static String defaultSideTag(String marketTicker) {
        int index = marketTicker.lastIndexOf('-');
        return index < 0 || index == marketTicker.length() - 1 ? null : marketTicker.substring(index + 1);
    }
}
