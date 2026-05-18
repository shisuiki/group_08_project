package edu.illinois.group8.storage.db;

public record CanonicalDbEvent(
    String eventId,
    String rawEventId,
    String replayId,
    String streamName,
    String eventType,
    int schemaVersion,
    String marketTicker,
    Long eventTsMs,
    Long ingestTsNs,
    Long publishTsNs,
    String payload
) {
}
