package edu.illinois.group8.storage.db;

public record MarketMetadataReadRequest(
    String marketTicker,
    String seriesTicker,
    String status,
    int maxRows
) {
    public static final int DEFAULT_MAX_ROWS = 100;
    public static final int MAX_ROWS = 1_000;

    public MarketMetadataReadRequest {
        marketTicker = normalize(marketTicker);
        seriesTicker = normalize(seriesTicker);
        status = normalize(status);
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        maxRows = Math.min(maxRows, MAX_ROWS);
    }

    public static MarketMetadataReadRequest byTicker(String marketTicker) {
        MarketMetadataReadRequest request = new MarketMetadataReadRequest(marketTicker, null, null, 1);
        if (request.marketTicker() == null) {
            throw new IllegalArgumentException("marketTicker must not be blank");
        }
        return request;
    }

    public static MarketMetadataReadRequest search(String seriesTicker, String status, int maxRows) {
        return new MarketMetadataReadRequest(null, seriesTicker, status, maxRows);
    }

    public static MarketMetadataReadRequest defaultSearch() {
        return new MarketMetadataReadRequest(null, null, null, DEFAULT_MAX_ROWS);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
