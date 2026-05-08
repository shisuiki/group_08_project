package edu.illinois.group8.replay.raw;

public interface RawIngressReplayPublisher extends AutoCloseable {
    boolean publish(String rawPayload);

    @Override
    default void close() {
    }
}
