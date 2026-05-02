package edu.illinois.group8.publication;

import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.canonical.StreamRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamRegistryTest {
    @Test
    void everyCanonicalEventTypeHasAStreamContract() {
        for (EventType eventType : EventType.values()) {
            assertTrue(StreamRegistry.byName(eventType.streamName()).isPresent(), eventType.streamName());
            assertEquals(1, StreamRegistry.byName(eventType.streamName()).orElseThrow().schemaVersion());
        }
    }
}
