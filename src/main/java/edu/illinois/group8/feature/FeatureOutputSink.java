package edu.illinois.group8.feature;

public interface FeatureOutputSink extends AutoCloseable {
    void write(FeatureOutput output);

    @Override
    default void close() {
    }
}
