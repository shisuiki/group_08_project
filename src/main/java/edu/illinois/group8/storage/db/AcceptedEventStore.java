package edu.illinois.group8.storage.db;

import java.util.List;

public interface AcceptedEventStore {
    /*
     * Async writers may call raw and canonical batch methods concurrently from
     * independent workers. Implementations must keep each batch operation safe
     * under that raw/canonical concurrency boundary.
     */
    void insertRawBatch(List<RawWsDbEvent> events) throws Exception;

    void insertCanonicalBatch(List<CanonicalDbEvent> events) throws Exception;
}
