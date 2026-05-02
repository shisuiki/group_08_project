package edu.illinois.group8.canonical;

public interface CanonicalEvent {
    String eventId();

    String eventType();

    int schemaVersion();

    String streamName();

    EventMetadata metadata();
}
