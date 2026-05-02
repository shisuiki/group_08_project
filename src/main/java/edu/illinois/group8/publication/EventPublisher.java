package edu.illinois.group8.publication;

import edu.illinois.group8.canonical.CanonicalEvent;

public interface EventPublisher {
    boolean publish(CanonicalEvent event);
}
