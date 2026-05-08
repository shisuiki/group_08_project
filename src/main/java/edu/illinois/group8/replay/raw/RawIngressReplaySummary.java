package edu.illinois.group8.replay.raw;

public record RawIngressReplaySummary(
    String replayId,
    long sourceEventsLoaded,
    long eventsAttempted,
    long eventsPublished,
    long publishFailures,
    long elapsedNs
) {
    public double publishedPerSecond() {
        if (elapsedNs <= 0L) {
            return 0.0;
        }
        return eventsPublished / (elapsedNs / 1_000_000_000.0);
    }
}
