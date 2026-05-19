package edu.illinois.group8.storage.db;

import java.time.Instant;

public record RawRestDbResponse(
    String rawRestResponseId,
    String endpoint,
    String ticker,
    long fetchTsNs,
    Instant fetchWallTs,
    String payloadSha256,
    String rawPayload
) {
}
