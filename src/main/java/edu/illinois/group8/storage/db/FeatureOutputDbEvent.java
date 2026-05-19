package edu.illinois.group8.storage.db;

import java.util.Objects;

public record FeatureOutputDbEvent(
    String featureEventId,
    String sourceEventId,
    String featureName,
    int featureVersion,
    String marketTicker,
    Long eventTsMs,
    String values
) {
    public FeatureOutputDbEvent {
        if (featureEventId == null || featureEventId.isBlank()) {
            throw new IllegalArgumentException("featureEventId must not be blank");
        }
        if (featureName == null || featureName.isBlank()) {
            throw new IllegalArgumentException("featureName must not be blank");
        }
        Objects.requireNonNull(values, "values");
    }
}
