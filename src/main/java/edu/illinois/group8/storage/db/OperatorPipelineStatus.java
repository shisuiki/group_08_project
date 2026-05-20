package edu.illinois.group8.storage.db;

public record OperatorPipelineStatus(
    String status,
    String cursorName,
    long cursorCommitSeq,
    long canonicalMaxCommitSeq,
    long cursorLagEvents,
    Long latestMarketStateCommitSeq,
    Long latestStateAgeMs,
    String error
) {
    public static OperatorPipelineStatus disabled() {
        return new OperatorPipelineStatus("disabled", "", 0L, 0L, 0L, null, null, null);
    }

    public static OperatorPipelineStatus unavailable(String cursorName, String error) {
        return new OperatorPipelineStatus(
            "unavailable",
            cursorName == null ? "" : cursorName.trim(),
            0L,
            0L,
            0L,
            null,
            null,
            error
        );
    }
}
