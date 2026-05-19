package edu.illinois.group8.feature;

import edu.illinois.group8.storage.db.FeatureOutputStore;

import java.util.Objects;

public final class DbFeatureOutputSink implements FeatureOutputSink {
    private final FeatureOutputStore store;
    private final FeatureOutputDbEventMapper mapper;

    public DbFeatureOutputSink(FeatureOutputStore store) {
        this(store, new FeatureOutputDbEventMapper());
    }

    DbFeatureOutputSink(FeatureOutputStore store, FeatureOutputDbEventMapper mapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void write(FeatureOutput output) {
        try {
            store.insertFeatureOutput(mapper.toDbEvent(output));
        } catch (Exception e) {
            String featureName = output == null ? "<null>" : output.featureName();
            throw new IllegalStateException("Failed to write feature output " + featureName + " to DB", e);
        }
    }
}
