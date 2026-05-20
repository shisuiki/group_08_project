package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.CanonicalDbCursor;
import edu.illinois.group8.storage.db.CanonicalDbEventReader;
import edu.illinois.group8.storage.db.CanonicalDbReadEvent;
import edu.illinois.group8.storage.db.CanonicalDbReadRequest;
import edu.illinois.group8.storage.db.FeaturePlantProjectorLease;
import edu.illinois.group8.storage.db.FeatureOutputDbEvent;
import edu.illinois.group8.storage.db.FeatureOutputProjectionStore;
import edu.illinois.group8.storage.db.LatestMarketState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class FeaturePlantDbProjector implements AutoCloseable {
    private static final String SERVICE = "featureplant";
    private static final String DB_OUTPUT_EVENTS = "featureplant_db_output_events_total";
    private static final String DB_OUTPUT_QUEUE_DEPTH = "featureplant_db_output_queue_depth";
    private static final String PROJECTOR_EVENTS = "featureplant_db_projector_events_total";
    private static final String PROJECTOR_OUTPUTS = "featureplant_db_projector_outputs_total";
    private static final String PROJECTOR_CURSOR = "featureplant_db_projector_cursor_commit_seq";
    private static final String PROJECTOR_LAG = "featureplant_db_projector_lag_events";

    private final CanonicalDbEventReader reader;
    private final FeatureOutputProjectionStore projectionStore;
    private final List<String> streams;
    private final List<FeatureModule> modules;
    private final ObjectMapper mapper;
    private final FeatureOutputDbEventMapper outputMapper;
    private final long maxEvents;
    private final boolean includeReplayEvents;
    private final String replayId;
    private final String cursorName;
    private final BackendMetrics metrics;
    private final FeaturePlantProjectorLease lease;
    private final ConcurrentHashMap<FeatureMetricKey, FeatureMetricHandles> metricHandles = new ConcurrentHashMap<>();
    private final BackendMetrics.Counter dbOutputAccepted;
    private final BackendMetrics.Counter dbOutputWritten;
    private final BackendMetrics.Counter dbOutputFailed;
    private final BackendMetrics.Counter projectorEventsRead;
    private final BackendMetrics.Counter projectorEventsProjected;
    private final BackendMetrics.Counter projectorEventsFailed;
    private final BackendMetrics.Counter projectorOutputsWritten;
    private final BackendMetrics.Counter projectorOutputsFailed;

    private CanonicalDbCursor cursor;
    private long consumedEvents;
    private long moduleDistributionSampleCursor;

    public FeaturePlantDbProjector(
        CanonicalDbEventReader reader,
        FeatureOutputProjectionStore projectionStore,
        List<StreamContract> streams,
        List<FeatureModule> modules,
        long maxEvents,
        boolean includeReplayEvents,
        String replayId,
        String cursorName,
        BackendMetrics metrics
    ) {
        this(
            reader,
            projectionStore,
            streamNames(streams),
            modules,
            maxEvents,
            includeReplayEvents,
            replayId,
            cursorName,
            metrics,
            new JsonCanonicalSerializer().mapper(),
            new FeatureOutputDbEventMapper(),
            FeaturePlantProjectorLease.NOOP
        );
    }

    FeaturePlantDbProjector(
        CanonicalDbEventReader reader,
        FeatureOutputProjectionStore projectionStore,
        List<String> streams,
        List<FeatureModule> modules,
        long maxEvents,
        boolean includeReplayEvents,
        String replayId,
        String cursorName,
        BackendMetrics metrics,
        ObjectMapper mapper,
        FeatureOutputDbEventMapper outputMapper
    ) {
        this(
            reader,
            projectionStore,
            streams,
            modules,
            maxEvents,
            includeReplayEvents,
            replayId,
            cursorName,
            metrics,
            mapper,
            outputMapper,
            FeaturePlantProjectorLease.NOOP
        );
    }

    FeaturePlantDbProjector(
        CanonicalDbEventReader reader,
        FeatureOutputProjectionStore projectionStore,
        List<String> streams,
        List<FeatureModule> modules,
        long maxEvents,
        boolean includeReplayEvents,
        String replayId,
        String cursorName,
        BackendMetrics metrics,
        ObjectMapper mapper,
        FeatureOutputDbEventMapper outputMapper,
        FeaturePlantProjectorLease lease
    ) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.projectionStore = Objects.requireNonNull(projectionStore, "projectionStore");
        this.streams = List.copyOf(Objects.requireNonNull(streams, "streams"));
        this.modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        this.maxEvents = Math.max(0L, maxEvents);
        this.includeReplayEvents = includeReplayEvents;
        this.replayId = replayId == null ? "" : replayId.trim();
        this.cursorName = normalizeCursorName(cursorName);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.outputMapper = Objects.requireNonNull(outputMapper, "outputMapper");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.cursor = projectionStore.loadCursor(this.cursorName).orElse(CanonicalDbCursor.start());
        this.dbOutputAccepted = counter(DB_OUTPUT_EVENTS, "result", "accepted");
        this.dbOutputWritten = counter(DB_OUTPUT_EVENTS, "result", "written");
        this.dbOutputFailed = counter(DB_OUTPUT_EVENTS, "result", "failed");
        this.projectorEventsRead = counter(PROJECTOR_EVENTS, "result", "read");
        this.projectorEventsProjected = counter(PROJECTOR_EVENTS, "result", "projected");
        this.projectorEventsFailed = counter(PROJECTOR_EVENTS, "result", "failed");
        this.projectorOutputsWritten = counter(PROJECTOR_OUTPUTS, "result", "written");
        this.projectorOutputsFailed = counter(PROJECTOR_OUTPUTS, "result", "failed");
        this.metrics.setGauge(DB_OUTPUT_QUEUE_DEPTH, BackendMetrics.labels("service", SERVICE), 0L);
        updateCursorGauge();
        updateLagGauge(0L);
        for (FeatureModule module : modules) {
            metrics.setGauge(
                "feature_module_enabled",
                BackendMetrics.labels("service", SERVICE, "module", module.name(), "stream", ""),
                1L
            );
        }
    }

    public int poll(int fragmentLimit) {
        int readLimit = readLimit(fragmentLimit);
        if (readLimit <= 0) {
            return 0;
        }

        List<CanonicalDbReadEvent> events = reader.read(new CanonicalDbReadRequest(
            cursor,
            streams,
            List.of(),
            replayId,
            includeReplayEvents,
            readLimit
        ));

        if (events.isEmpty()) {
            updateLagGauge(0L);
            return 0;
        }

        List<FeatureOutputDbEvent> outputs = new ArrayList<>();
        List<LatestMarketState> latestStates = new ArrayList<>();
        CanonicalDbCursor nextCursor = cursor;
        int projectedEvents = 0;
        long lastReadCommitSeq = cursor.lastCommitSeq();
        try {
            for (CanonicalDbReadEvent event : events) {
                if (projectedEvents >= readLimit) {
                    break;
                }
                if (maxEvents > 0L && consumedEvents + projectedEvents >= maxEvents) {
                    break;
                }
                lastReadCommitSeq = event.canonicalCommitSeq();
                CanonicalEnvelope envelope = CanonicalEnvelope.fromPayload(event.streamName(), event.payload(), mapper);
                for (FeatureOutput output : collectOutputs(envelope)) {
                    FeatureOutputDbEvent dbEvent = outputMapper.toDbEvent(output);
                    outputs.add(dbEvent);
                    latestStateFor(output, dbEvent, event.canonicalCommitSeq()).ifPresent(latestStates::add);
                }
                nextCursor = event.nextCursor();
                projectedEvents++;
            }
            if (projectedEvents == 0) {
                updateLagGauge(0L);
                return 0;
            }
            projectorEventsRead.add(projectedEvents);
            updateLagGauge(Math.max(0L, lastReadCommitSeq - cursor.lastCommitSeq()));
            if (!outputs.isEmpty()) {
                dbOutputAccepted.add(outputs.size());
            }
            lease.ensureHeld();
            commitProjection(nextCursor, outputs, latestStates);
            cursor = nextCursor;
            consumedEvents += projectedEvents;
            projectorEventsProjected.add(projectedEvents);
            if (!outputs.isEmpty()) {
                dbOutputWritten.add(outputs.size());
                projectorOutputsWritten.add(outputs.size());
            }
            updateCursorGauge();
            updateLagGauge(0L);
            return projectedEvents;
        } catch (RuntimeException e) {
            recordProjectionFailure(outputs.size());
            throw e;
        }
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

    CanonicalDbCursor cursor() {
        return cursor;
    }

    long consumedEvents() {
        return consumedEvents;
    }

    @Override
    public void close() {
        lease.close();
    }

    private List<FeatureOutput> collectOutputs(CanonicalEnvelope envelope) {
        List<FeatureOutput> outputs = new ArrayList<>();
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
                    outputs.add(output);
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
        return outputs;
    }

    private java.util.Optional<LatestMarketState> latestStateFor(
        FeatureOutput output,
        FeatureOutputDbEvent dbEvent,
        long canonicalCommitSeq
    ) {
        if (!BestBidOfferFeatureModule.FEATURE_NAME.equals(output.featureName())) {
            return java.util.Optional.empty();
        }
        String marketTicker = dbEvent.marketTicker();
        if (marketTicker == null || marketTicker.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new LatestMarketState(
            marketTicker,
            dbEvent.eventTsMs(),
            dbEvent.sourceEventId(),
            canonicalCommitSeq,
            longValue(output.values().get("bid_price_micros")),
            longValue(output.values().get("ask_price_micros")),
            longValue(output.values().get("midpoint_micros")),
            longValue(output.values().get("open_interest")),
            dbEvent.values()
        ));
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private void commitProjection(
        CanonicalDbCursor nextCursor,
        List<FeatureOutputDbEvent> outputs,
        List<LatestMarketState> latestStates
    ) {
        try {
            projectionStore.commitProjection(cursorName, nextCursor, outputs, latestStates);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to commit FeaturePlant DB projection cursor " + cursorName, e);
        }
    }

    private void recordProjectionFailure(int outputCount) {
        projectorEventsFailed.increment();
        if (outputCount > 0) {
            dbOutputFailed.add(outputCount);
            projectorOutputsFailed.add(outputCount);
        }
    }

    private int readLimit(int fragmentLimit) {
        long limit = fragmentLimit <= 0 ? DbCanonicalEnvelopeSource.DEFAULT_UNBOUNDED_POLL_LIMIT : fragmentLimit;
        if (maxEvents > 0L) {
            long remaining = maxEvents - consumedEvents;
            if (remaining <= 0L) {
                return 0;
            }
            limit = Math.min(limit, remaining);
        }
        return (int) Math.min(limit, Integer.MAX_VALUE);
    }

    private boolean shouldSampleModuleDistributions() {
        return FeaturePlantService.shouldSampleHotPathDistribution(moduleDistributionSampleCursor++);
    }

    private BackendMetrics.Counter counter(String metricName, String labelKey, String labelValue) {
        return metrics.counter(metricName, BackendMetrics.labels("service", SERVICE, labelKey, labelValue));
    }

    private void updateCursorGauge() {
        metrics.setGauge(
            PROJECTOR_CURSOR,
            BackendMetrics.labels("service", SERVICE, "cursor", cursorName),
            cursor.lastCommitSeq()
        );
    }

    private void updateLagGauge(long lagEvents) {
        metrics.setGauge(
            PROJECTOR_LAG,
            BackendMetrics.labels("service", SERVICE, "cursor", cursorName),
            Math.max(0L, lagEvents)
        );
    }

    private FeatureMetricHandles createFeatureMetricHandles(FeatureMetricKey key) {
        Map<String, String> labels = BackendMetrics.labels(
            "service", SERVICE,
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

    private static String normalizeCursorName(String cursorName) {
        if (cursorName == null || cursorName.isBlank()) {
            throw new IllegalArgumentException("cursorName must be non-blank");
        }
        return cursorName.trim();
    }

    private static List<String> streamNames(List<StreamContract> streams) {
        Objects.requireNonNull(streams, "streams");
        return streams.stream()
            .map(StreamContract::streamName)
            .toList();
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
