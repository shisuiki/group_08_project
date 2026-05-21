package edu.illinois.group8.storage.db;

import java.util.List;

public record MarketCapabilityPage(
    MarketCapabilitySummary summary,
    List<MarketCapability> markets,
    long totalCount,
    int limit,
    int offset
) {
    public MarketCapabilityPage {
        summary = summary == null ? MarketCapabilitySummary.empty() : summary;
        markets = markets == null ? List.of() : List.copyOf(markets);
        if (totalCount < 0 || limit < 1 || offset < 0) {
            throw new IllegalArgumentException("invalid page bounds");
        }
    }
}
