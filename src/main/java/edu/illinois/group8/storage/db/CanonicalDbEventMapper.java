package edu.illinois.group8.storage.db;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

import java.util.Objects;

public final class CanonicalDbEventMapper {
    private final JsonCanonicalSerializer serializer;

    public CanonicalDbEventMapper() {
        this(new JsonCanonicalSerializer());
    }

    public CanonicalDbEventMapper(JsonCanonicalSerializer serializer) {
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    public CanonicalDbEvent toDbEvent(CanonicalEvent event) {
        Objects.requireNonNull(event, "event");
        EventMetadata metadata = Objects.requireNonNull(event.metadata(), "event.metadata");
        String payload = serializer.toJson(event);
        return new CanonicalDbEvent(
            event.eventId(),
            metadata.rawEventId(),
            metadata.replayId(),
            event.streamName(),
            event.eventType(),
            event.schemaVersion(),
            metadata.marketTicker(),
            metadata.eventTsMs(),
            metadata.ingestTsNs(),
            metadata.publishTsNs(),
            payload
        );
    }
}
