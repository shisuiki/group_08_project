package edu.illinois.group8.storage.db;

import java.util.List;

public interface MarketMetadataReader {
    List<MarketMetadata> read(MarketMetadataReadRequest request);
}
