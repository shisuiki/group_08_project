package edu.illinois.group8.replay;

public record ReplaySummary(
    String replayId,
    long rawEventsRead,
    long canonicalEventsPublished,
    long skippedEvents
) {
}
