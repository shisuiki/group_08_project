package edu.illinois.group8.replay.raw;

import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.metrics.BackendMetrics;

public class RawIngressReplayCli {
    public static void main(String[] args) {
        if (helpRequested(args)) {
            System.out.println(RawIngressReplayConfig.usage());
            return;
        }
        RawIngressReplayConfig config;
        try {
            config = RawIngressReplayConfig.fromEnvironment().withCliArgs(args);
            config.validateForReplay();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        BackendMetrics metrics = new BackendMetrics();
        try (
            RawReplaySource source = RawReplaySourceFactory.fromConfig(config);
            RawIngressReplayPublisher publisher = config.dryRun()
                ? new CollectingRawIngressReplayPublisher()
                : new ClusterRawIngressReplayPublisher(BackendConfig.fromEnvironment())
        ) {
            RawIngressReplayService service = new RawIngressReplayService(source, metrics);
            RawIngressReplaySummary summary = service.replay(config, publisher);
            printSummary(config, source, summary, metrics);
        }
    }

    private static void printSummary(
        RawIngressReplayConfig config,
        RawReplaySource source,
        RawIngressReplaySummary summary,
        BackendMetrics metrics
    ) {
        System.out.println("raw_replay_id=" + summary.replayId());
        System.out.println("raw_replay_source=" + source.description());
        System.out.println("selection_start_receive_ts_ns=" + config.startReceiveTsNs());
        System.out.println("selection_end_receive_ts_ns=" + config.endReceiveTsNs());
        System.out.println("selection_market_tickers=" + String.join(",", config.marketTickers()));
        System.out.println("selection_raw_event_ids=" + String.join(",", config.rawEventIds()));
        System.out.println("selection_max_events=" + config.maxEvents());
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

    private static boolean helpRequested(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
