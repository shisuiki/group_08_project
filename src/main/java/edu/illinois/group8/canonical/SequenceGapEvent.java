package edu.illinois.group8.canonical;

public record SequenceGapEvent(
    String eventId,
    String eventType,
    int schemaVersion,
    String streamName,
    EventMetadata metadata,
    Long expectedSequence,
    Long actualSequence,
    String reason,
    String recoveryAction
) implements CanonicalEvent {
    public SequenceGapEvent(
        String eventId,
        EventMetadata metadata,
        Long expectedSequence,
        Long actualSequence,
        String reason,
        String recoveryAction
    ) {
        this(
            eventId,
            EventType.SEQUENCE_GAP.eventType(),
            EventType.SEQUENCE_GAP.schemaVersion(),
            EventType.SEQUENCE_GAP.streamName(),
            metadata,
            expectedSequence,
            actualSequence,
            reason,
            recoveryAction
        );
    }
}
