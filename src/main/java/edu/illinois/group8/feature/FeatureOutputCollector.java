package edu.illinois.group8.feature;

@FunctionalInterface
public interface FeatureOutputCollector {
    void emit(FeatureOutput output);
}
