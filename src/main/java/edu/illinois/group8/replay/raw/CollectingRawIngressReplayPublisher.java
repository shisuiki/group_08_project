package edu.illinois.group8.replay.raw;

import java.util.ArrayList;
import java.util.List;

public class CollectingRawIngressReplayPublisher implements RawIngressReplayPublisher {
    private final List<String> payloads = new ArrayList<>();

    @Override
    public boolean publish(String rawPayload) {
        payloads.add(rawPayload);
        return true;
    }

    public List<String> payloads() {
        return List.copyOf(payloads);
    }
}
