package edu.illinois.group8.storage.db;

public record MarketMetadataReadRequest(
    String marketTicker,
    String seriesTicker,
    String status,
    int maxRows,
    String excludeGeneratedTaxonomyVersion
) {
    public static final int DEFAULT_MAX_ROWS = 100;
    public static final int MAX_ROWS = 200_000;

    public MarketMetadataReadRequest {
        marketTicker = normalize(marketTicker);
        seriesTicker = normalize(seriesTicker);
        status = normalize(status);
        excludeGeneratedTaxonomyVersion = normalize(excludeGeneratedTaxonomyVersion);
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        maxRows = Math.min(maxRows, MAX_ROWS);
    }

    public static MarketMetadataReadRequest byTicker(String marketTicker) {
        MarketMetadataReadRequest request = new MarketMetadataReadRequest(marketTicker, null, null, 1, null);
        if (request.marketTicker() == null) {
            throw new IllegalArgumentException("marketTicker must not be blank");
        }
        return request;
    }

    public static MarketMetadataReadRequest search(String seriesTicker, String status, int maxRows) {
        return new MarketMetadataReadRequest(null, seriesTicker, status, maxRows, null);
    }

    public static MarketMetadataReadRequest searchWithoutGenerated(
        String seriesTicker,
        String status,
        int maxRows,
        String taxonomyVersion
    ) {
        MarketMetadataReadRequest request =
            new MarketMetadataReadRequest(null, seriesTicker, status, maxRows, taxonomyVersion);
        if (request.excludeGeneratedTaxonomyVersion() == null) {
            throw new IllegalArgumentException("taxonomyVersion must not be blank");
        }
        return request;
    }

    public static MarketMetadataReadRequest defaultSearch() {
        return new MarketMetadataReadRequest(null, null, null, DEFAULT_MAX_ROWS, null);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
