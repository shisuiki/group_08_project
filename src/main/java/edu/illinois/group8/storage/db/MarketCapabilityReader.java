package edu.illinois.group8.storage.db;

public interface MarketCapabilityReader {
    MarketCapabilityPage readPage(MarketCapabilityReadRequest request);
}
