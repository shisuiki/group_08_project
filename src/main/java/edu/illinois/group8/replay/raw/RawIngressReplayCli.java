package edu.illinois.group8.replay.raw;

import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.metrics.BackendMetrics;

public class RawIngressReplayCli {
    public static void main(String[] args) {
        RawIngressReplayConfig config = RawIngressReplayConfig.fromEnvironment().withCliArgs(args);
        BackendMetrics metrics = new BackendMetrics();
        RawIngressReplayService service = new RawIngressReplayService(
            new RawRecordingReader(config.rawRecordingRoot()),
            metrics
        );
        try (RawIngressReplayPublisher publisher = config.dryRun()
            ? new CollectingRawIngressReplayPublisher()
            : new ClusterRawIngressReplayPublisher(BackendConfig.fromEnvironment())) {
            RawIngressReplaySummary summary = service.replay(config, publisher);
            printSummary(config, summary, metrics);
        }
    }

    private static void printSummary(
        RawIngressReplayConfig config,
        RawIngressReplaySummary summary,
        BackendMetrics metrics
    ) {
        System.out.println("raw_replay_id=" + summary.replayId());
        System.out.println("raw_recording_root=" + config.rawRecordingRoot());
        System.out.println("mode=" + config.mode().name().toLowerCase(java.util.Locale.ROOT));
        System.out.println("dry_run=" + config.dryRun());
        System.out.println("source_events_loaded=" + summary.sourceEventsLoaded());
        System.out.println("events_attempted=" + summary.eventsAttempted());
        System.out.println("events_published=" + summary.eventsPublished());
        System.out.println("publish_failures=" + summary.publishFailures());
        System.out.printf(java.util.Locale.ROOT, "elapsed_ms=%.3f%n", summary.elapsedNs() / 1_000_000.0);
        System.out.printf(java.util.Locale.ROOT, "published_per_second=%.2f%n", summary.publishedPerSecond());
        System.out.println();
        System.out.println("# Prometheus metrics");
        System.out.print(metrics.prometheusText());
    }
}
