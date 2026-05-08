package edu.illinois.group8.replay.raw;

import edu.illinois.group8.metrics.BackendMetrics;

import java.util.List;

public class RawIngressReplayService {
    private final RawRecordingReader reader;
    private final BackendMetrics metrics;

    public RawIngressReplayService(RawRecordingReader reader, BackendMetrics metrics) {
        this.reader = reader;
        this.metrics = metrics;
    }

    public RawIngressReplaySummary replay(RawIngressReplayConfig config, RawIngressReplayPublisher publisher) {
        List<RawReplayEvent> events = reader.read(config.maxEvents());
        long attempted = 0L;
        long published = 0L;
        long failures = 0L;
        long startTsNs = System.nanoTime();
        metrics.setGauge("backend_replay_sessions_active", 1L);
        try {
            Long previousReceiveTsNs = null;
            long nextFixedRateDeadlineNs = System.nanoTime();
            for (RawReplayEvent event : events) {
                pace(config, previousReceiveTsNs, event.receiveTsNs(), nextFixedRateDeadlineNs);
                if (config.mode() == RawReplayMode.FIXED_RATE && config.fixedRatePerSecond() > 0L) {
                    nextFixedRateDeadlineNs += Math.max(1L, 1_000_000_000L / config.fixedRatePerSecond());
                }
                previousReceiveTsNs = event.receiveTsNs() == null ? previousReceiveTsNs : event.receiveTsNs();
                attempted++;
                boolean ok = config.dryRun() || publisher.publish(event, config.replayId());
                var labels = BackendMetrics.labels("service", "raw_ingress_replay", "replay_id", config.replayId());
                metrics.increment("backend_replay_events_attempted_total", labels);
                if (ok) {
                    published++;
                    metrics.increment("backend_replay_events_published_total", labels);
                } else {
                    failures++;
                    metrics.increment("backend_replay_publish_failed_total", labels);
                }
            }
        } finally {
            metrics.setGauge("backend_replay_sessions_active", 0L);
        }
        return new RawIngressReplaySummary(
            config.replayId(),
            events.size(),
            attempted,
            published,
            failures,
            Math.max(0L, System.nanoTime() - startTsNs)
        );
    }

    private void pace(RawIngressReplayConfig config, Long previousTsNs, Long currentTsNs, long nextFixedRateDeadlineNs) {
        switch (config.mode()) {
            case AS_FAST_AS_POSSIBLE -> {
            }
            case ORIGINAL_TIMESTAMPS -> {
                if (previousTsNs != null && currentTsNs != null) {
                    long deltaNs = Math.max(0L, currentTsNs - previousTsNs);
                    sleepNs(Math.round(deltaNs / config.speedMultiplier()));
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
