package edu.illinois.group8.storage.db;

import edu.illinois.group8.feature.FeatureOutput;

import java.time.Instant;
import java.util.Objects;

public record FeatureOutputRow(String featureEventId, Instant createdAt, FeatureOutput output) {
    public FeatureOutputRow {
        if (featureEventId == null || featureEventId.isBlank()) {
            throw new IllegalArgumentException("featureEventId is required");
        }
        featureEventId = featureEventId.trim();
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
        output = Objects.requireNonNull(output, "output");
    }

    public FeatureOutputCursor cursor() {
        return new FeatureOutputCursor(createdAt, featureEventId);
    }
}
