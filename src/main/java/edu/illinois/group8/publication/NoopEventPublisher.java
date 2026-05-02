package edu.illinois.group8.publication;

import edu.illinois.group8.canonical.CanonicalEvent;

public class NoopEventPublisher implements EventPublisher {
    @Override
    public boolean publish(CanonicalEvent event) {
        return true;
    }
}
