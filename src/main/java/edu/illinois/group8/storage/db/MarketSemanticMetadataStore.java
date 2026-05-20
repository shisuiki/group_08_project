package edu.illinois.group8.storage.db;

import java.util.Optional;

public interface MarketSemanticMetadataStore {
    void upsertMetadata(MarketSemanticMetadata metadata);

    void upsertJob(MarketSemanticMetadataJob job);

    Optional<MarketSemanticMetadata> findMetadata(String marketTicker, String taxonomyVersion);
}
