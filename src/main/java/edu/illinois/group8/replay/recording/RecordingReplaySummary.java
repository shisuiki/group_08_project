package edu.illinois.group8.replay.recording;

import java.util.Map;

public record RecordingReplaySummary(
    String replayId,
    long sourceEventsLoaded,
    long eventsAttempted,
    long eventsPublished,
    long publishFailures,
    long elapsedNs,
    Map<String, Long> eventsByStream
) {
    public double elapsedSeconds() {
        return elapsedNs / 1_000_000_000.0;
    }

    public double publishedPerSecond() {
        double seconds = elapsedSeconds();
        return seconds <= 0.0 ? 0.0 : eventsPublished / seconds;
    }
}
