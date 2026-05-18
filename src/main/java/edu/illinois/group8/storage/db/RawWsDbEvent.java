package edu.illinois.group8.storage.db;

import java.time.Instant;

public record RawWsDbEvent(
    String rawEventId,
    String source,
    String captureId,
    String connectionId,
    long connectionSequence,
    long receiveTsNs,
    Instant receiveWallTs,
    String marketTicker,
    String sourceChannel,
    Long sourceSequence,
    String payloadSha256,
    String rawPayload,
    String ingestStatus
) {
}
