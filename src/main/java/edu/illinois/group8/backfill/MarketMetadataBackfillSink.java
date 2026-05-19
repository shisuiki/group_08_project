package edu.illinois.group8.backfill;

import edu.illinois.group8.storage.db.MarketMetadata;

import java.util.List;

interface MarketMetadataBackfillSink {
    void writeBatch(List<MarketMetadata> metadata) throws Exception;
}
