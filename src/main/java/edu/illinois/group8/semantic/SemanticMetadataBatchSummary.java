package edu.illinois.group8.semantic;

import java.math.BigDecimal;

public record SemanticMetadataBatchSummary(
    int processed,
    int generated,
    int reviewRequired,
    int rateLimited,
    int failed,
    int skipped,
    int paidRequests,
    BigDecimal estimatedSpendUsd,
    String primaryModel,
    String fallbackModel
) {
    public String toSummaryLine() {
        return "processed=" + processed
            + " generated=" + generated
            + " review_required=" + reviewRequired
            + " rate_limited=" + rateLimited
            + " failed=" + failed
            + " skipped=" + skipped
            + " paid_requests=" + paidRequests
            + " estimated_spend_usd=" + estimatedSpendUsd
            + " model=" + primaryModel
            + " fallback_model=" + fallbackModel;
    }
}
