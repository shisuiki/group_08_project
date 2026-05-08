package edu.illinois.group8.feature;

public interface CanonicalEnvelopeSource extends AutoCloseable {
    int poll(CanonicalEnvelopeHandler handler, int fragmentLimit);

    @Override
    default void close() {
    }
}
