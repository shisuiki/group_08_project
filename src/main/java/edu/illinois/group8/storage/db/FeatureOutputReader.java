package edu.illinois.group8.storage.db;

import edu.illinois.group8.feature.FeatureOutput;

import java.util.List;

public interface FeatureOutputReader {
    List<FeatureOutput> read(FeatureOutputReadRequest request);
}
