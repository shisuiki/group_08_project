package edu.illinois.group8.feature;

@FunctionalInterface
public interface CanonicalEnvelopeHandler {
    void onEvent(CanonicalEnvelope envelope);
}
