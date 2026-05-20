package edu.illinois.group8.storage.db;

public record OperatorLatencyStatus(
    String status,
    String sourceEventId,
    String marketTicker,
    Long canonicalCommitSeq,
    Long latestMarketStateCommitSeq,
    Long canonicalToFeatureMs,
    Long featureToLatestStateMs,
    Long canonicalToLatestStateMs,
    String reason,
    String error
) {
    public static OperatorLatencyStatus disabled() {
        return new OperatorLatencyStatus("disabled", "", "", null, null, null, null, null, "db_not_configured", null);
    }

    public static OperatorLatencyStatus missing(String sourceEventId, String reason) {
        return new OperatorLatencyStatus(
            "missing",
            sourceEventId == null ? "" : sourceEventId.trim(),
            "",
            null,
            null,
            null,
            null,
            null,
            reason == null || reason.isBlank() ? "missing_latency_data" : reason,
            null
        );
    }

    public static OperatorLatencyStatus unavailable(String sourceEventId, String error) {
        return new OperatorLatencyStatus(
            "unavailable",
            sourceEventId == null ? "" : sourceEventId.trim(),
            "",
            null,
            null,
            null,
            null,
            null,
            "reader_error",
            error
        );
    }
}
