package edu.illinois.group8.feature;

import java.util.ArrayList;
import java.util.List;

public class CollectingFeatureOutputSink implements FeatureOutputSink {
    private final List<FeatureOutput> outputs = new ArrayList<>();

    @Override
    public synchronized void write(FeatureOutput output) {
        outputs.add(output);
    }

    public synchronized List<FeatureOutput> outputs() {
        return List.copyOf(outputs);
    }
}
