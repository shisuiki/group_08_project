package edu.illinois.group8.replay;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public record ReplayOptions(
    Path journalRoot,
    List<String> marketTickers,
    Long startTsMs,
    Long endTsMs,
    ReplayMode mode,
    double speedMultiplier,
    String replayId
) {
    public ReplayOptions {
        marketTickers = List.copyOf(marketTickers == null ? List.of() : marketTickers);
        mode = mode == null ? ReplayMode.MULTIPLIER : mode;
        speedMultiplier = speedMultiplier <= 0 ? 1.0 : speedMultiplier;
        replayId = replayId == null || replayId.isBlank() ? "replay-" + UUID.randomUUID() : replayId;
    }
}
