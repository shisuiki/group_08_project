package edu.illinois.group8.storage.db;

public record MarketCapability(
    String marketTicker,
    String eventTicker,
    String seriesTicker,
    String status,
    String catalogSource,
    boolean hasLatestState,
    boolean hasQuote,
    Long quoteEventTsMs,
    Long quoteAgeMs,
    String quoteStatus,
    boolean hasBboHistory,
    boolean chartableFromBbo,
    boolean chartableFromTickerSnapshot,
    boolean chartableFromTradeTape,
    String bestChartSource,
    boolean chartable1h,
    boolean chartable24h,
    boolean chartable,
    String chartStatus,
    String chartReason,
    String semanticStatus,
    String semanticSector,
    String semanticSubsector,
    String semanticEventType,
    long featureCount,
    long bboSampleCount,
    long tradeSampleCount,
    long tickerSampleCount,
    long historyBars24hCount,
    long trade24hCount,
    long quote24hCount,
    Long lastEventTsMs,
    Long liquidityRank,
    boolean displayEligible
) {
    public MarketCapability {
        marketTicker = nonBlank(marketTicker, "marketTicker");
        status = value(status);
        catalogSource = value(catalogSource);
        quoteStatus = value(quoteStatus);
        bestChartSource = value(bestChartSource);
        chartStatus = value(chartStatus);
        chartReason = value(chartReason);
        semanticStatus = value(semanticStatus);
        if (featureCount < 0 || bboSampleCount < 0 || tradeSampleCount < 0 || tickerSampleCount < 0
            || historyBars24hCount < 0 || trade24hCount < 0 || quote24hCount < 0
            || (liquidityRank != null && liquidityRank < 0)) {
            throw new IllegalArgumentException("feature/sample counts must be non-negative");
        }
    }

    public boolean metadataOnly() {
        return "market_metadata".equals(catalogSource)
            && !hasQuote
            && !hasBboHistory
            && featureCount == 0L;
    }

    public boolean semanticTagged() {
        return !"missing".equals(semanticStatus);
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value.trim();
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
