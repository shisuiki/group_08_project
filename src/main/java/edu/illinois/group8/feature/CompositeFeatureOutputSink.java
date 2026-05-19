package edu.illinois.group8.feature;

import java.util.List;
import java.util.Objects;

public final class CompositeFeatureOutputSink implements FeatureOutputSink {
    private final List<FeatureOutputSink> sinks;

    public CompositeFeatureOutputSink(List<FeatureOutputSink> sinks) {
        Objects.requireNonNull(sinks, "sinks");
        if (sinks.isEmpty()) {
            throw new IllegalArgumentException("sinks must not be empty");
        }
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void write(FeatureOutput output) {
        for (FeatureOutputSink sink : sinks) {
            sink.write(output);
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (FeatureOutputSink sink : sinks) {
            try {
                sink.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
