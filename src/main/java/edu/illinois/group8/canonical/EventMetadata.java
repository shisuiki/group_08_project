package edu.illinois.group8.canonical;

public record EventMetadata(
    String source,
    String sourceChannel,
    Long sourceSubscriptionId,
    Long sourceSequence,
    String marketTicker,
    String marketId,
    Long eventTsMs,
    long ingestTsNs,
    Long publishTsNs,
    String rawEventId,
    String replayId
) {
    public EventMetadata withPublishTsNs(long publishTsNs) {
        return new EventMetadata(
            source,
            sourceChannel,
            sourceSubscriptionId,
            sourceSequence,
            marketTicker,
            marketId,
            eventTsMs,
            ingestTsNs,
            publishTsNs,
            rawEventId,
            replayId
        );
    }

    public EventMetadata withReplayId(String replayId) {
        return new EventMetadata(
            source,
            sourceChannel,
            sourceSubscriptionId,
            sourceSequence,
            marketTicker,
            marketId,
            eventTsMs,
            ingestTsNs,
            publishTsNs,
            rawEventId,
            replayId
        );
    }
}
