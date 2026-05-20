package edu.illinois.group8.storage.db;

import java.util.List;

public interface SemanticMarketMetadataReader {
    List<SemanticMarketMetadataRow> read(SemanticMarketMetadataReadRequest request);
}
