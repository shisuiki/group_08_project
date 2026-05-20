package edu.illinois.group8.backfill;

public record CatalogSyncSummary(
    int pagesFetched,
    int marketsDiscovered,
    int marketsSelected,
    int rowsUpserted,
    int dryRunRows,
    int failures,
    String nextCursor,
    boolean hasMore
) {
    public CatalogSyncSummary {
        if (pagesFetched < 0 || marketsDiscovered < 0 || marketsSelected < 0 || rowsUpserted < 0
            || dryRunRows < 0 || failures < 0) {
            throw new IllegalArgumentException("catalog sync summary counters must be non-negative");
        }
        nextCursor = nextCursor == null ? "" : nextCursor.trim();
    }
}
