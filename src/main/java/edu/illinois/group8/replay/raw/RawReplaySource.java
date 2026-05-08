package edu.illinois.group8.replay.raw;

import java.util.List;

public interface RawReplaySource extends AutoCloseable {
    List<RawReplayEvent> read(RawReplaySelection selection);

    String description();

    @Override
    default void close() {
    }
}
