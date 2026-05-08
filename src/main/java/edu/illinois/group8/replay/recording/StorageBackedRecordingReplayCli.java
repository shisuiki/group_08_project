package edu.illinois.group8.replay.recording;

import edu.illinois.group8.metrics.BackendMetrics;

public class StorageBackedRecordingReplayCli {
    public static void main(String[] args) {
        RecordingReplayConfig config = RecordingReplayConfig.fromEnvironment().withCliArgs(args);
        BackendMetrics metrics = new BackendMetrics();
        StorageBackedRecordingReplayService service = new StorageBackedRecordingReplayService(
            new RecordingEventReader(config.recordingRoot()),
            metrics
        );

        try (RecordingReplayPublisher publisher = config.dryRun()
            ? new CollectingRecordingReplayPublisher()
            : new AeronRecordingReplayPublisher(config.aeronChannel(), metrics)) {
            RecordingReplaySummary summary = service.replay(config, publisher);
            printSummary(config, summary, metrics);
        }
    }

    private static void printSummary(
        RecordingReplayConfig config,
        RecordingReplaySummary summary,
        BackendMetrics metrics
    ) {
        System.out.println("recording_replay_id=" + summary.replayId());
        System.out.println("recording_root=" + config.recordingRoot());
        System.out.println("mode=" + config.mode().name().toLowerCase(java.util.Locale.ROOT));
        System.out.println("dry_run=" + config.dryRun());
        System.out.println("source_events_loaded=" + summary.sourceEventsLoaded());
        System.out.println("events_attempted=" + summary.eventsAttempted());
        System.out.println("events_published=" + summary.eventsPublished());
        System.out.println("publish_failures=" + summary.publishFailures());
        System.out.printf(java.util.Locale.ROOT, "elapsed_ms=%.3f%n", summary.elapsedNs() / 1_000_000.0);
        System.out.printf(java.util.Locale.ROOT, "published_per_second=%.2f%n", summary.publishedPerSecond());
        System.out.println("events_by_stream=" + summary.eventsByStream());
        System.out.println();
        System.out.println("# Prometheus metrics");
        System.out.print(metrics.prometheusText());
    }
}
