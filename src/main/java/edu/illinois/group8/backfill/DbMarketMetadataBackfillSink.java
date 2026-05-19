package edu.illinois.group8.backfill;

import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.MarketMetadataStore;

import java.util.List;
import java.util.Objects;

final class DbMarketMetadataBackfillSink implements MarketMetadataBackfillSink {
    private final MarketMetadataStore store;

    DbMarketMetadataBackfillSink(MarketMetadataStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void writeBatch(List<MarketMetadata> metadata) throws Exception {
        if (metadata.isEmpty()) {
            return;
        }
        for (MarketMetadata entry : metadata) {
            store.upsertMarketMetadata(entry);
        }
    }
}
