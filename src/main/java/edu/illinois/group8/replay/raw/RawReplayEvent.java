package edu.illinois.group8.replay.raw;

public record RawReplayEvent(
    String rawPayload,
    Long receiveTsNs,
    String connectionId,
    long sequence,
    String rawEventId,
    String marketTicker,
    String sourceName,
    String sourcePosition
) {
}
