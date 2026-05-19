package edu.illinois.group8.storage.db;

import java.util.List;
import java.util.Objects;

public interface FeatureOutputStore {
    void insertFeatureOutput(FeatureOutputDbEvent output) throws Exception;

    default void insertFeatureOutputBatch(List<FeatureOutputDbEvent> outputs) throws Exception {
        Objects.requireNonNull(outputs, "outputs");
        for (FeatureOutputDbEvent output : outputs) {
            insertFeatureOutput(Objects.requireNonNull(output, "output"));
        }
    }
}
