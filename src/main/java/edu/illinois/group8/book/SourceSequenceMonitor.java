package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.parser.KalshiCanonicalParser;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SourceSequenceMonitor {
    private final Map<Long, Long> lastSequenceBySubscription;

    public SourceSequenceMonitor() {
        this(Map.of());
    }

    public SourceSequenceMonitor(Map<Long, Long> restoredWatermarks) {
        this.lastSequenceBySubscription = copyWatermarks(restoredWatermarks);
    }

    public Map<Long, Long> snapshotWatermarks() {
        return Collections.unmodifiableMap(new HashMap<>(lastSequenceBySubscription));
    }

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

        Long previousSequence = lastSequenceBySubscription.get(subscriptionId);
        if (previousSequence == null) {
            lastSequenceBySubscription.put(subscriptionId, actualSequence);
            return List.of();
        }

        long expectedSequence = previousSequence + 1L;
        if (actualSequence == expectedSequence) {
            lastSequenceBySubscription.put(subscriptionId, actualSequence);
            return List.of();
        }

        boolean nonMonotonic = actualSequence <= previousSequence;
        if (!nonMonotonic) {
            lastSequenceBySubscription.put(subscriptionId, actualSequence);
        }

        String reason = nonMonotonic ? "non_monotonic_source_sequence" : "source_sequence_gap";
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

    private static Map<Long, Long> copyWatermarks(Map<Long, Long> watermarks) {
        if (watermarks == null) {
            throw new IllegalArgumentException("Source sequence watermarks must not be null.");
        }
        Map<Long, Long> copy = new HashMap<>();
        for (Map.Entry<Long, Long> entry : watermarks.entrySet()) {
            Long subscriptionId = entry.getKey();
            Long sequence = entry.getValue();
            if (subscriptionId == null) {
                throw new IllegalArgumentException("Source sequence watermark subscription id must not be null.");
            }
            if (subscriptionId < 0L) {
                throw new IllegalArgumentException("Source sequence watermark subscription id must not be negative.");
            }
            if (sequence == null) {
                throw new IllegalArgumentException("Source sequence watermark sequence must not be null.");
            }
            if (sequence < 0L) {
                throw new IllegalArgumentException("Source sequence watermark sequence must not be negative.");
            }
            copy.put(subscriptionId, sequence);
        }
        return copy;
    }
}
