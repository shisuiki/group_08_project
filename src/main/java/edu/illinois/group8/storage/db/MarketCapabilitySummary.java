package edu.illinois.group8.storage.db;

public record MarketCapabilitySummary(
    long totalAssets,
    long chartableCount,
    long quoteCount,
    long staleQuoteCount,
    long semanticGeneratedCount,
    long semanticReviewRequiredCount,
    long semanticFailedCount,
    long semanticRateLimitedCount,
    long semanticMissingCount,
    long metadataOnlyCount
) {
    public MarketCapabilitySummary {
        if (totalAssets < 0 || chartableCount < 0 || quoteCount < 0
            || staleQuoteCount < 0 || semanticGeneratedCount < 0 || semanticReviewRequiredCount < 0
            || semanticFailedCount < 0 || semanticRateLimitedCount < 0 || semanticMissingCount < 0
            || metadataOnlyCount < 0) {
            throw new IllegalArgumentException("summary counts must be non-negative");
        }
    }

    public static MarketCapabilitySummary empty() {
        return new MarketCapabilitySummary(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
}
