package edu.illinois.group8.replay.raw;

import java.nio.file.Path;

public record RawReplayEvent(
    String rawPayload,
    Long receiveTsNs,
    String connectionId,
    long sequence,
    Path sourceFile,
    long sourceLine
) {
}
