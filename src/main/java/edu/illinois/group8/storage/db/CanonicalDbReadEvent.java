package edu.illinois.group8.storage.db;

public record CanonicalDbReadEvent(
    long canonicalCommitSeq,
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
    public CanonicalDbReadEvent {
        if (canonicalCommitSeq <= 0L) {
            throw new IllegalArgumentException("canonicalCommitSeq must be positive");
        }
    }

    public CanonicalDbCursor nextCursor() {
        return new CanonicalDbCursor(canonicalCommitSeq);
    }

    public CanonicalDbEvent canonicalDbEvent() {
        return new CanonicalDbEvent(
            eventId,
            rawEventId,
            replayId,
            streamName,
            eventType,
            schemaVersion,
            marketTicker,
            eventTsMs,
            ingestTsNs,
            publishTsNs,
            payload
        );
    }
}
