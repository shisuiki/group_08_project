package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.parser.KalshiCanonicalParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SourceSequenceMonitor {
    private final Map<Long, Long> lastSequenceBySubscription = new HashMap<>();

    public List<CanonicalEvent> apply(CanonicalEvent event) {
        if (event == null || event.metadata() == null) {
            return List.of();
        }
        EventMetadata metadata = event.metadata();
        Long subscriptionId = metadata.sourceSubscriptionId();
        Long actualSequence = metadata.sourceSequence();
        if (subscriptionId == null || actualSequence == null) {
            return List.of();
        }

        Long previousSequence = lastSequenceBySubscription.put(subscriptionId, actualSequence);
        if (previousSequence == null) {
            return List.of();
        }

        long expectedSequence = previousSequence + 1L;
        if (actualSequence == expectedSequence) {
            return List.of();
        }

        String reason = actualSequence <= previousSequence
            ? "non_monotonic_source_sequence"
            : "source_sequence_gap";
        return List.of(new SequenceGapEvent(
            KalshiCanonicalParser.eventId(
                metadata.rawEventId(),
                "sequence_gap",
                reason,
                "sid-" + subscriptionId
            ),
            metadata,
            expectedSequence,
            actualSequence,
            reason,
            "inspect_source_subscription_and_reconnect"
        ));
    }
}
