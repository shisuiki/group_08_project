package edu.illinois.group8.storage.db;

public interface LatestMarketStateStore {
    void upsertLatestMarketState(LatestMarketState state) throws Exception;
}
