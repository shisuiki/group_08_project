package edu.illinois.group8.publication;

import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.canonical.StreamRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamRegistryTest {
    @Test
    void everyCanonicalEventTypeHasAStreamContract() {
        for (EventType eventType : EventType.values()) {
            assertTrue(StreamRegistry.byName(eventType.streamName()).isPresent(), eventType.streamName());
            assertEquals(1, StreamRegistry.byName(eventType.streamName()).orElseThrow().schemaVersion());
        }
    }

    @Test
    void externalAeronStreamIdsAreUniqueAndNormalizedDefaultExcludesRaw() {
        HashSet<Integer> ids = new HashSet<>();
        for (var stream : StreamRegistry.externalStreams()) {
            assertTrue(ids.add(stream.streamId()), stream.streamName());
        }
        assertFalse(StreamRegistry.normalizedStreams().stream()
            .anyMatch(stream -> EventType.RAW_SOURCE_EVENT.streamName().equals(stream.streamName())));
    }
}
