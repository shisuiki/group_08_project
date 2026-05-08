package edu.illinois.group8.replay.recording;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CollectingRecordingReplayPublisher implements RecordingReplayPublisher {
    private final List<RecordingEvent> events = new ArrayList<>();
    private final List<String> payloads = new ArrayList<>();

    @Override
    public boolean publish(RecordingEvent event, String payload) {
        events.add(event);
        payloads.add(payload);
        return true;
    }

    public List<RecordingEvent> events() {
        return Collections.unmodifiableList(events);
    }

    public List<String> payloads() {
        return Collections.unmodifiableList(payloads);
    }
}
