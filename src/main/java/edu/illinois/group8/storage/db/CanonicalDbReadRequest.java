package edu.illinois.group8.storage.db;

import java.util.List;

public record CanonicalDbReadRequest(
    CanonicalDbCursor cursor,
    List<String> streams,
    List<String> marketTickers,
    String replayId,
    boolean includeReplayEvents,
    int maxEvents
) {
    public CanonicalDbReadRequest {
        cursor = cursor == null ? CanonicalDbCursor.start() : cursor;
        streams = normalizeValues(streams);
        marketTickers = normalizeValues(marketTickers);
        replayId = normalizeValue(replayId);
    }

    public static CanonicalDbReadRequest fromStart() {
        return new CanonicalDbReadRequest(CanonicalDbCursor.start(), List.of(), List.of(), null, false, 0);
    }

    private static List<String> normalizeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(CanonicalDbReadRequest::normalizeValue)
            .filter(value -> !value.isEmpty())
            .toList();
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }
}
