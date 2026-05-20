package edu.illinois.group8.storage.db;

import java.time.Instant;

public record MarketSemanticMetadataJob(
    String jobId,
    String marketTicker,
    String taxonomyVersion,
    String promptHash,
    String sourcePayloadSha256,
    String sourceFingerprint,
    String idempotencyKey,
    String requestedModel,
    String actualModel,
    String status,
    int attempts,
    Instant nextRetryAt,
    String error,
    String usage
) {
    public MarketSemanticMetadataJob {
        jobId = nonBlank(jobId, "jobId");
        marketTicker = nonBlank(marketTicker, "marketTicker");
        taxonomyVersion = nonBlank(taxonomyVersion, "taxonomyVersion");
        promptHash = nonBlank(promptHash, "promptHash");
        sourcePayloadSha256 = nonBlank(sourcePayloadSha256, "sourcePayloadSha256");
        sourceFingerprint = nonBlank(sourceFingerprint, "sourceFingerprint");
        idempotencyKey = nonBlank(idempotencyKey, "idempotencyKey");
        requestedModel = nonBlank(requestedModel, "requestedModel");
        status = nonBlank(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be non-negative");
        }
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value.trim();
    }
}
