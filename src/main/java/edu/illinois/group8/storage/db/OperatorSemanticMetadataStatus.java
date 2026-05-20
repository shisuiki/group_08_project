package edu.illinois.group8.storage.db;

import java.time.Instant;

public record OperatorSemanticMetadataStatus(
    String status,
    boolean configured,
    String model,
    String fallbackModel,
    String taxonomyVersion,
    long generatedCount,
    long reviewRequiredCount,
    long failedCount,
    long rateLimitedCount,
    Instant lastGeneratedAt,
    Long lastGeneratedAgeMs,
    String error
) {
    public static OperatorSemanticMetadataStatus disabled(String model, String fallbackModel, String taxonomyVersion) {
        return new OperatorSemanticMetadataStatus(
            "disabled",
            false,
            value(model),
            value(fallbackModel),
            value(taxonomyVersion),
            0L,
            0L,
            0L,
            0L,
            null,
            null,
            null
        );
    }

    public static OperatorSemanticMetadataStatus unavailable(
        String model,
        String fallbackModel,
        String taxonomyVersion,
        String error
    ) {
        return new OperatorSemanticMetadataStatus(
            "unavailable",
            true,
            value(model),
            value(fallbackModel),
            value(taxonomyVersion),
            0L,
            0L,
            0L,
            0L,
            null,
            null,
            error
        );
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
