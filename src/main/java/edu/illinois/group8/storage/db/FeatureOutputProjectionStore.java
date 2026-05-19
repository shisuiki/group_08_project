package edu.illinois.group8.storage.db;

import java.util.List;
import java.util.Optional;

public interface FeatureOutputProjectionStore {
    Optional<CanonicalDbCursor> loadCursor(String cursorName);

    void commitProjection(String cursorName, CanonicalDbCursor cursor, List<FeatureOutputDbEvent> outputs) throws Exception;
}
