package edu.illinois.group8.canonical;

import java.util.List;

public record OrderBookSnapshotEvent(
    String eventId,
    String eventType,
    int schemaVersion,
    String streamName,
    EventMetadata metadata,
    List<PriceLevel> yesBids,
    List<PriceLevel> noBids
) implements CanonicalEvent {
    public OrderBookSnapshotEvent(
        String eventId,
        EventMetadata metadata,
        List<PriceLevel> yesBids,
        List<PriceLevel> noBids
    ) {
        this(
            eventId,
            EventType.ORDER_BOOK_SNAPSHOT.eventType(),
            EventType.ORDER_BOOK_SNAPSHOT.schemaVersion(),
            EventType.ORDER_BOOK_SNAPSHOT.streamName(),
            metadata,
            List.copyOf(yesBids),
            List.copyOf(noBids)
        );
    }
}
