package edu.illinois.group8.storage.db;

import java.util.List;

public interface CanonicalDbEventReader {
    List<CanonicalDbReadEvent> read(CanonicalDbReadRequest request);
}
