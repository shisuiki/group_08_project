package edu.illinois.group8.replay.recording;

public interface RecordingReplayPublisher extends AutoCloseable {
    boolean publish(RecordingEvent event, String payload);

    @Override
    default void close() {
    }
}
