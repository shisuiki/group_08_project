package edu.illinois.group8.replay.recording;

import java.nio.file.Path;

public record RecordingEvent(
    String eventId,
    String eventType,
    String streamName,
    Long eventTsMs,
    Long recorderCommitTsNs,
    Path sourceFile,
    long sourceLine,
    String payload
) {
}
