package edu.illinois.group8.storage.db;

import java.util.List;

public record FeatureOutputReadRequest(
    List<String> featureNames,
    String marketTicker,
    Long fromEventTsMs,
    Long toEventTsMs,
    int maxRows
) {
    public static final int DEFAULT_MAX_ROWS = 10_000;

    public FeatureOutputReadRequest {
        featureNames = normalizeList(featureNames);
        marketTicker = normalize(marketTicker);
        if (fromEventTsMs != null && toEventTsMs != null && fromEventTsMs > toEventTsMs) {
            throw new IllegalArgumentException("fromEventTsMs must be <= toEventTsMs");
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
    }

    public static FeatureOutputReadRequest recent(List<String> featureNames, int maxRows) {
        return new FeatureOutputReadRequest(featureNames, null, null, null, maxRows);
    }

    public static FeatureOutputReadRequest defaultRecent() {
        return recent(List.of(), DEFAULT_MAX_ROWS);
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(FeatureOutputReadRequest::normalize)
            .filter(value -> value != null)
            .distinct()
            .toList();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
