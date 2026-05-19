package edu.illinois.group8.storage.db;

import java.util.Optional;

public interface FeaturePlantCursorStore {
    Optional<CanonicalDbCursor> loadCursor(String cursorName);

    void saveCursor(String cursorName, CanonicalDbCursor cursor);
}
