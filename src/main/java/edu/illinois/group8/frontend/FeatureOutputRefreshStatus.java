package edu.illinois.group8.frontend;

import java.time.Instant;

public record FeatureOutputRefreshStatus(
    boolean enabled,
    boolean running,
    Instant lastSuccessAt,
    Instant lastErrorAt,
    String lastError,
    int lastRowCount,
    long totalLoaded,
    long refreshErrors
) {
    public static FeatureOutputRefreshStatus disabled() {
        return new FeatureOutputRefreshStatus(false, false, null, null, null, 0, 0L, 0L);
    }

    FeatureOutputRefreshStatus withRunning(boolean running) {
        return new FeatureOutputRefreshStatus(
            enabled,
            running,
            lastSuccessAt,
            lastErrorAt,
            lastError,
            lastRowCount,
            totalLoaded,
            refreshErrors
        );
    }
}
