package edu.illinois.group8.storage.db;

import java.time.Instant;

public record RawWsDbEventInput(
    String source,
    String captureId,
    String connectionId,
    long connectionSequence,
    long receiveTsNs,
    Instant receiveWallTs,
    String rawPayload,
    String ingestStatus
) {
}
