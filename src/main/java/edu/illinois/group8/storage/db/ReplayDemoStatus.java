package edu.illinois.group8.storage.db;

import java.util.List;

public record ReplayDemoStatus(
    String status,
    String replayId,
    long marketCount,
    long canonicalEventCount,
    long featureOutputCount,
    long latestMarketStateCount,
    Long firstEventTsMs,
    Long lastEventTsMs,
    Long firstCanonicalCommitSeq,
    Long lastCanonicalCommitSeq,
    List<String> availableSymbols,
    boolean featurePlantProjected,
    String error
) {
    public ReplayDemoStatus {
        status = status == null || status.isBlank() ? "unknown" : status.trim();
        replayId = replayId == null ? "" : replayId.trim();
        availableSymbols = availableSymbols == null ? List.of() : List.copyOf(availableSymbols);
    }

    public static ReplayDemoStatus disabled(String replayId) {
        return empty("disabled", replayId, null);
    }

    public static ReplayDemoStatus unavailable(String replayId, String error) {
        return empty("unavailable", replayId, error);
    }

    public static ReplayDemoStatus fromCounts(
        String replayId,
        long marketCount,
        long canonicalEventCount,
        long featureOutputCount,
        long latestMarketStateCount,
        Long firstEventTsMs,
        Long lastEventTsMs,
        Long firstCanonicalCommitSeq,
        Long lastCanonicalCommitSeq,
        List<String> availableSymbols
    ) {
        boolean projected = featureOutputCount > 0L;
        String status = canonicalEventCount <= 0L ? "empty" : projected ? "projected" : "seeded";
        return new ReplayDemoStatus(
            status,
            replayId,
            marketCount,
            canonicalEventCount,
            featureOutputCount,
            latestMarketStateCount,
            firstEventTsMs,
            lastEventTsMs,
            firstCanonicalCommitSeq,
            lastCanonicalCommitSeq,
            availableSymbols,
            projected,
            null
        );
    }

    private static ReplayDemoStatus empty(String status, String replayId, String error) {
        return new ReplayDemoStatus(
            status,
            replayId,
            0L,
            0L,
            0L,
            0L,
            null,
            null,
            null,
            null,
            List.of(),
            false,
            error
        );
    }
}
