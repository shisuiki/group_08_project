package edu.illinois.group8.storage.db;

import java.time.Instant;

public record FeatureOutputCursor(Instant createdAt, String featureEventId) {
    public FeatureOutputCursor {
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
        if (featureEventId == null || featureEventId.isBlank()) {
            throw new IllegalArgumentException("featureEventId is required");
        }
        featureEventId = featureEventId.trim();
    }
}
