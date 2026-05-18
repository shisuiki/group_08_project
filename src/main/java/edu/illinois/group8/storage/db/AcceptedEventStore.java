package edu.illinois.group8.storage.db;

import java.util.List;

public interface AcceptedEventStore {
    void insertRawBatch(List<RawWsDbEvent> events) throws Exception;

    void insertCanonicalBatch(List<CanonicalDbEvent> events) throws Exception;
}
