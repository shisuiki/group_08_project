package edu.illinois.group8.feature;

import edu.illinois.group8.metrics.BackendMetrics;

import java.util.List;
import java.util.Map;

public class FeaturePlantService implements AutoCloseable {
    static final int HOT_PATH_DISTRIBUTION_SAMPLE_MASK = 63;

    private final CanonicalEnvelopeSource source;
    private final List<FeatureModule> modules;
    private final FeatureOutputSink sink;
    private final BackendMetrics metrics;
    private long moduleDistributionSampleCursor;

    public FeaturePlantService(CanonicalEnvelopeSource source, List<FeatureModule> modules, FeatureOutputSink sink) {
        this(source, modules, sink, new BackendMetrics());
    }

    public FeaturePlantService(
        CanonicalEnvelopeSource source,
        List<FeatureModule> modules,
        FeatureOutputSink sink,
        BackendMetrics metrics
    ) {
        this.source = source;
        this.modules = List.copyOf(modules);
        this.sink = sink;
        this.metrics = metrics;
        for (FeatureModule module : modules) {
            metrics.setGauge("feature_module_enabled", labels(module, ""), 1L);
        }
    }

    public int poll(int fragmentLimit) {
        return source.poll(this::dispatch, fragmentLimit);
    }

    public long runUntilExhausted(int batchSize) {
        long total = 0L;
        int polled;
        do {
            polled = poll(batchSize);
            total += polled;
        } while (polled > 0);
        return total;
    }

    public String metricsText() {
        return metrics.prometheusText();
    }

    @Override
    public void close() {
        try {
            sink.close();
        } finally {
            source.close();
        }
    }

    private void dispatch(CanonicalEnvelope envelope) {
        for (FeatureModule module : modules) {
            if (!module.accepts(envelope)) {
                continue;
            }
            Map<String, String> labels = labels(module, envelope.streamName());
            boolean sampleDistributions = shouldSampleModuleDistributions();
            long startNs = sampleDistributions ? System.nanoTime() : 0L;
            metrics.increment("feature_module_events_in_total", labels);
            try {
                module.onEvent(envelope, output -> {
                    sink.write(output);
                    metrics.increment("feature_module_events_out_total", labels);
                });
            } catch (RuntimeException e) {
                metrics.increment("feature_module_errors_total", labels);
                throw e;
            } finally {
                if (sampleDistributions) {
                    metrics.observe("feature_module_latency_ns", labels, Math.max(0L, System.nanoTime() - startNs));
                }
                if (sampleDistributions && envelope.eventTsMs() != null) {
                    metrics.observe("feature_module_lag_ms", labels, Math.max(0L, System.currentTimeMillis() - envelope.eventTsMs()));
                }
            }
        }
    }

    static boolean shouldSampleHotPathDistribution(long cursor) {
        return (cursor & HOT_PATH_DISTRIBUTION_SAMPLE_MASK) == 0L;
    }

    private boolean shouldSampleModuleDistributions() {
        return shouldSampleHotPathDistribution(moduleDistributionSampleCursor++);
    }

    private Map<String, String> labels(FeatureModule module, String streamName) {
        return BackendMetrics.labels("service", "featureplant", "module", module.name(), "stream", streamName);
    }
}
