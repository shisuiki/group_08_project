package edu.illinois.group8.storage.db;

public interface MarketMetadataStore {
    void upsertMarketMetadata(MarketMetadata metadata) throws Exception;
}
