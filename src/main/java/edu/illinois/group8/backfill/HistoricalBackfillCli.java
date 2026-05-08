package edu.illinois.group8.backfill;

import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.KalshiRestParser;
import edu.illinois.group8.recorder.CanonicalRecordingWriter;
import edu.illinois.group8.wrapper.KalshiWrapper;

public final class HistoricalBackfillCli {
    private HistoricalBackfillCli() {
    }

    public static void main(String[] args) {
        HistoricalBackfillConfig config = HistoricalBackfillConfig.fromEnvironment().validate();
        BackendMetrics metrics = new BackendMetrics();
        CanonicalRecordingWriter canonicalWriter = new CanonicalRecordingWriter(
            config.outputRoot(),
            config.canonicalSubtree(),
            config.timestampSource(),
            metrics,
            config.partitionGranularity(),
            "historical_backfill",
            "backfill_metadata",
            "rest_fetch_ts_ns"
        );
        RawRestResponseWriter rawWriter = config.rawRestOutputRoot() == null
            ? null
            : new RawRestResponseWriter(config.rawRestOutputRoot(), config.partitionGranularity());
        HistoricalBackfillService service = new HistoricalBackfillService(
            new KalshiHistoricalBackfillClient(new KalshiWrapper(
                config.kalshiBaseUrl(),
                config.kalshiKeyId(),
                config.kalshiKeyPath()
            )),
            new KalshiRestParser(),
            canonicalWriter,
            rawWriter,
            metrics
        );
        HistoricalBackfillSummary summary = service.run(config);
        System.out.println("historical_backfill_rest_responses_fetched=" + summary.restResponsesFetched());
        System.out.println("historical_backfill_raw_responses_recorded=" + summary.rawResponsesRecorded());
        System.out.println("historical_backfill_canonical_events_parsed=" + summary.canonicalEventsParsed());
        System.out.println("historical_backfill_canonical_events_recorded=" + summary.canonicalEventsRecorded());
        System.out.println("historical_backfill_markets_discovered=" + summary.marketsDiscovered());
        System.out.println("historical_backfill_failures=" + summary.failures());
    }
}
