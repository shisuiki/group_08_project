package edu.illinois.group8.storage.db;

public interface FeatureOutputStore {
    void insertFeatureOutput(FeatureOutputDbEvent output) throws Exception;
}
