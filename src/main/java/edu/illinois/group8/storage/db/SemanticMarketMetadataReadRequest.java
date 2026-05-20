package edu.illinois.group8.storage.db;

public record SemanticMarketMetadataReadRequest(
    String taxonomyVersion,
    String marketTicker,
    String semanticStatus,
    String marketStatus,
    String tag,
    String query,
    int maxRows
) {
    public static final int DEFAULT_MAX_ROWS = 200;
    public static final int MAX_ROWS = 500;

    public SemanticMarketMetadataReadRequest {
        taxonomyVersion = nonBlank(taxonomyVersion, "taxonomyVersion");
        marketTicker = normalize(marketTicker);
        semanticStatus = normalize(semanticStatus);
        marketStatus = normalize(marketStatus);
        tag = normalize(tag);
        query = normalize(query);
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        maxRows = Math.min(maxRows, MAX_ROWS);
    }

    public static SemanticMarketMetadataReadRequest defaultForTaxonomy(String taxonomyVersion) {
        return new SemanticMarketMetadataReadRequest(
            taxonomyVersion,
            null,
            null,
            null,
            null,
            null,
            DEFAULT_MAX_ROWS
        );
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
}
