package edu.illinois.group8.feature;

import java.util.Set;

public interface FeatureModule {
    String name();

    Set<String> inputStreams();

    default boolean accepts(CanonicalEnvelope envelope) {
        return inputStreams().contains(envelope.streamName());
    }

    void onEvent(CanonicalEnvelope envelope, FeatureOutputCollector collector);
}
