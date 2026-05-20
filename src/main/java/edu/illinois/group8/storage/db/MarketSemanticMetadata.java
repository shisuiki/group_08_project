package edu.illinois.group8.storage.db;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record MarketSemanticMetadata(
    String marketTicker,
    String taxonomyVersion,
    String model,
    String promptVersion,
    String promptHash,
    String sourcePayloadSha256,
    String sourceFingerprint,
    String idempotencyKey,
    String sector,
    String subsector,
    String eventType,
    String region,
    String timeHorizon,
    String liquidityBucket,
    String riskBucket,
    String tags,
    BigDecimal confidence,
    String rationale,
    String rawResponse,
    String status,
    String error,
    Instant generatedAt
) {
    public MarketSemanticMetadata {
        marketTicker = nonBlank(marketTicker, "marketTicker");
        taxonomyVersion = nonBlank(taxonomyVersion, "taxonomyVersion");
        model = nonBlank(model, "model");
        promptVersion = nonBlank(promptVersion, "promptVersion");
        promptHash = nonBlank(promptHash, "promptHash");
        sourcePayloadSha256 = nonBlank(sourcePayloadSha256, "sourcePayloadSha256");
        sourceFingerprint = nonBlank(sourceFingerprint, "sourceFingerprint");
        idempotencyKey = nonBlank(idempotencyKey, "idempotencyKey");
        tags = Objects.requireNonNull(tags, "tags");
        rawResponse = Objects.requireNonNull(rawResponse, "rawResponse");
        status = nonBlank(status, "status");
        if (confidence != null
            && (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value.trim();
    }
}
