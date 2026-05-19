package edu.illinois.group8.storage.db;

import java.util.List;

public interface RawRestResponseStore {
    void insertRawRestResponseBatch(List<RawRestDbResponse> responses) throws Exception;
}
