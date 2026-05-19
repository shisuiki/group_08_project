package edu.illinois.group8.publication;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.SerializedCanonicalEvent;

public interface EventPublisher {
    boolean publish(CanonicalEvent event);

    default boolean publish(SerializedCanonicalEvent event) {
        return publish(event.event());
    }

    default PublicationResult publishSerialized(CanonicalEvent event) {
        return new PublicationResult(event, null, publish(event));
    }

    record PublicationResult(
        CanonicalEvent event,
        SerializedCanonicalEvent serializedEvent,
        boolean success
    ) {
    }
}
