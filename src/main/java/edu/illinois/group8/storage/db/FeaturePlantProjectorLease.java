package edu.illinois.group8.storage.db;

public interface FeaturePlantProjectorLease extends AutoCloseable {
    FeaturePlantProjectorLease NOOP = () -> {
    };

    default void ensureHeld() {
    }

    @Override
    void close();
}
