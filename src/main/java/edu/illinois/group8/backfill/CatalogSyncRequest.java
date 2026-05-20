package edu.illinois.group8.backfill;

public record CatalogSyncRequest(
    boolean dryRun,
    String seriesTicker,
    String marketStatus,
    int limit,
    int maxPages,
    int maxTickers,
    String mveFilter
) {
    public CatalogSyncRequest {
        seriesTicker = normalize(seriesTicker);
        marketStatus = normalize(marketStatus);
        mveFilter = normalize(mveFilter);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be positive");
        }
        if (maxTickers < 0) {
            throw new IllegalArgumentException("maxTickers must be zero or positive");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
