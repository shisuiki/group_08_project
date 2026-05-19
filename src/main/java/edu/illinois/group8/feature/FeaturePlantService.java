package edu.illinois.group8.feature;

import edu.illinois.group8.metrics.BackendMetrics;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FeaturePlantService implements AutoCloseable {
    static final int HOT_PATH_DISTRIBUTION_SAMPLE_MASK = 63;

    private final CanonicalEnvelopeSource source;
    private final List<FeatureModule> modules;
    private final FeatureOutputSink sink;
    private final BackendMetrics metrics;
    private final ConcurrentHashMap<FeatureMetricKey, FeatureMetricHandles> metricHandles = new ConcurrentHashMap<>();
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
            FeatureMetricHandles handles = metricHandles.computeIfAbsent(
                FeatureMetricKey.from(module, envelope.streamName()),
                this::createFeatureMetricHandles
            );
            boolean sampleDistributions = shouldSampleModuleDistributions();
            long startNs = sampleDistributions ? System.nanoTime() : 0L;
            handles.eventsIn().increment();
            try {
                module.onEvent(envelope, output -> {
                    sink.write(output);
                    handles.eventsOut().increment();
                });
            } catch (RuntimeException e) {
                handles.errors().increment();
                throw e;
            } finally {
                if (sampleDistributions) {
                    handles.latency().observe(Math.max(0L, System.nanoTime() - startNs));
                }
                if (sampleDistributions && envelope.eventTsMs() != null) {
                    handles.lag().observe(Math.max(0L, System.currentTimeMillis() - envelope.eventTsMs()));
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

    private FeatureMetricHandles createFeatureMetricHandles(FeatureMetricKey key) {
        Map<String, String> labels = BackendMetrics.labels(
            "service", "featureplant",
            "module", key.moduleName(),
            "stream", key.streamName()
        );
        return new FeatureMetricHandles(
            metrics.counter("feature_module_events_in_total", labels),
            metrics.counter("feature_module_events_out_total", labels),
            metrics.counter("feature_module_errors_total", labels),
            metrics.distribution("feature_module_latency_ns", labels),
            metrics.distribution("feature_module_lag_ms", labels)
        );
    }

    private record FeatureMetricKey(String moduleName, String streamName) {
        private static FeatureMetricKey from(FeatureModule module, String streamName) {
            String normalizedStreamName = streamName == null || streamName.isBlank() ? "" : streamName;
            return new FeatureMetricKey(module.name(), normalizedStreamName);
        }
    }

    private record FeatureMetricHandles(
        BackendMetrics.Counter eventsIn,
        BackendMetrics.Counter eventsOut,
        BackendMetrics.Counter errors,
        BackendMetrics.DistributionHandle latency,
        BackendMetrics.DistributionHandle lag
    ) {
    }
}
