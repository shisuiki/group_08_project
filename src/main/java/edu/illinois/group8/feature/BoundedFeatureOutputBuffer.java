package edu.illinois.group8.feature;

import java.util.ArrayDeque;
import java.util.List;

public class BoundedFeatureOutputBuffer implements FeatureOutputSink {
    private final int maxOutputs;
    private final ArrayDeque<FeatureOutput> outputs = new ArrayDeque<>();

    public BoundedFeatureOutputBuffer(int maxOutputs) {
        this.maxOutputs = Math.max(1, maxOutputs);
    }

    @Override
    public synchronized void write(FeatureOutput output) {
        outputs.addLast(output);
        while (outputs.size() > maxOutputs) {
            outputs.removeFirst();
        }
    }

    public synchronized List<FeatureOutput> snapshot() {
        return List.copyOf(outputs);
    }
}
