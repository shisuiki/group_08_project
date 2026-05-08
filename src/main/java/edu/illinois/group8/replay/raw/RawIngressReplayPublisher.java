package edu.illinois.group8.replay.raw;

public interface RawIngressReplayPublisher extends AutoCloseable {
    boolean publish(RawReplayEvent event, String replayId);

    @Override
    default void close() {
    }
}
