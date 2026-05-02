package edu.illinois.group8.publication;

import edu.illinois.group8.canonical.CanonicalEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CollectingEventPublisher implements EventPublisher {
    private final List<CanonicalEvent> events = new ArrayList<>();

    @Override
    public boolean publish(CanonicalEvent event) {
        events.add(event);
        return true;
    }

    public List<CanonicalEvent> events() {
        return Collections.unmodifiableList(events);
    }
}
