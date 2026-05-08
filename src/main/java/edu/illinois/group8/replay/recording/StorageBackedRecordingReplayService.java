package edu.illinois.group8.replay.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.metrics.BackendMetrics;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StorageBackedRecordingReplayService {
    private final RecordingEventReader reader;
    private final BackendMetrics metrics;
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();

    public StorageBackedRecordingReplayService(RecordingEventReader reader, BackendMetrics metrics) {
        this.reader = reader;
        this.metrics = metrics;
    }

    public RecordingReplaySummary replay(RecordingReplayConfig config, RecordingReplayPublisher publisher) {
        List<RecordingEvent> events = reader.read(config.streams(), config.maxEvents());
        Map<String, Long> byStream = new LinkedHashMap<>();
        long attempted = 0L;
        long published = 0L;
        long failures = 0L;
        long startTsNs = System.nanoTime();
        metrics.setGauge("backend_replay_sessions_active", 1L);
        metrics.setGauge("backend_replay_speed_multiplier", Math.round(config.speedMultiplier()));

        try {
            for (int loop = 0; loop < config.loopCount(); loop++) {
                Long previousEventTsMs = null;
                long nextFixedRateDeadlineNs = System.nanoTime();
                for (RecordingEvent event : events) {
                    pace(config, previousEventTsMs, event.eventTsMs(), nextFixedRateDeadlineNs);
                    if (config.mode() == RecordingReplayMode.FIXED_RATE && config.fixedRatePerSecond() > 0L) {
                        nextFixedRateDeadlineNs += Math.max(1L, 1_000_000_000L / config.fixedRatePerSecond());
                    }
                    previousEventTsMs = event.eventTsMs() == null ? previousEventTsMs : event.eventTsMs();

                    attempted++;
                    String payload = config.annotateReplay() ? annotate(event, config.replayId(), loop) : event.payload();
                    boolean ok = config.dryRun() || publisher.publish(event, payload);
                    var labels = BackendMetrics.labels("service", "recording_replay", "stream", event.streamName(), "replay_id", config.replayId());
                    metrics.increment("backend_replay_events_attempted_total", labels);
                    if (ok) {
                        published++;
                        byStream.merge(event.streamName(), 1L, Long::sum);
                        metrics.increment("backend_replay_events_published_total", labels);
                    } else {
                        failures++;
                        metrics.increment("backend_replay_publish_failed_total", labels);
                    }
                    if (event.eventTsMs() != null) {
                        metrics.observe("backend_replay_lag_ms", labels, Math.max(0L, System.currentTimeMillis() - event.eventTsMs()));
                    }
                }
            }
        } finally {
            metrics.setGauge("backend_replay_sessions_active", 0L);
        }

        return new RecordingReplaySummary(
            config.replayId(),
            events.size(),
            attempted,
            published,
            failures,
            Math.max(0L, System.nanoTime() - startTsNs),
            Map.copyOf(byStream)
        );
    }

    private void pace(RecordingReplayConfig config, Long previousEventTsMs, Long eventTsMs, long nextFixedRateDeadlineNs) {
        switch (config.mode()) {
            case AS_FAST_AS_POSSIBLE -> {
            }
            case ORIGINAL_TIMESTAMPS -> {
                if (previousEventTsMs != null && eventTsMs != null) {
                    long deltaMs = Math.max(0L, eventTsMs - previousEventTsMs);
                    sleepNs(Math.round((deltaMs * 1_000_000.0) / config.speedMultiplier()));
                }
            }
            case FIXED_RATE -> {
                if (config.fixedRatePerSecond() > 0L) {
                    long waitNs = nextFixedRateDeadlineNs - System.nanoTime();
                    if (waitNs > 0L) {
                        sleepNs(waitNs);
                    }
                }
            }
        }
    }

    private String annotate(RecordingEvent event, String replayId, int loop) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(event.payload());
            ObjectNode replayMetadata = root.putObject("replay_metadata");
            replayMetadata.put("replay_id", replayId);
            replayMetadata.put("replay_loop", loop);
            replayMetadata.put("source_file", event.sourceFile().toString());
            replayMetadata.put("source_line", event.sourceLine());
            replayMetadata.put("replay_publish_ts_ns", System.nanoTime());
            replayMetadata.put("replay_source", "stream_recorder_storage");
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to annotate replay event " + event.eventId(), e);
        }
    }

    private void sleepNs(long ns) {
        if (ns <= 0L) {
            return;
        }
        long millis = ns / 1_000_000L;
        int nanos = (int) (ns % 1_000_000L);
        try {
            Thread.sleep(millis, nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
