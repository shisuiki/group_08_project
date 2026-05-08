package edu.illinois.group8.replay.raw;

import java.util.List;

public record RawReplaySelection(
    Long startReceiveTsNs,
    Long endReceiveTsNs,
    List<String> marketTickers,
    List<String> rawEventIds,
    long maxEvents
) {
    public RawReplaySelection {
        marketTickers = normalize(marketTickers);
        rawEventIds = normalize(rawEventIds);
    }

    public boolean isUnbounded() {
        return startReceiveTsNs == null
            && endReceiveTsNs == null
            && marketTickers.isEmpty()
            && rawEventIds.isEmpty()
            && maxEvents <= 0L;
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();
    }
}
