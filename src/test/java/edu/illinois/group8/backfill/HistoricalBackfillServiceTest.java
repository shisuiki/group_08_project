package edu.illinois.group8.backfill;

import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.KalshiRestParser;
import edu.illinois.group8.recorder.CanonicalRecordingWriter;
import edu.illinois.group8.recorder.StreamRecordingWriter;
import edu.illinois.group8.time.TimestampSource;
import edu.illinois.group8.wrapper.RequestParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalBackfillServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsRestBackfillIntoCanonicalAndRawRestLayouts() throws Exception {
        BackendMetrics metrics = new BackendMetrics();
        HistoricalBackfillService service = new HistoricalBackfillService(
            new FakeClient(),
            new KalshiRestParser(),
            new CanonicalRecordingWriter(
                tempDir,
                "canonical",
                TimestampSource.from("system_nano", "/dev/null"),
                metrics,
                StreamRecordingWriter.PartitionGranularity.MINUTE,
                "test_backfill",
                "backfill_metadata",
                "rest_fetch_ts_ns"
            ),
            new RawRestResponseWriter(
                tempDir.resolve("raw-rest"),
                StreamRecordingWriter.PartitionGranularity.MINUTE
            ),
            metrics
        );
        HistoricalBackfillConfig config = new HistoricalBackfillConfig(
            "https://api.elections.kalshi.com",
            "",
            "",
            tempDir,
            "canonical",
            tempDir.resolve("raw-rest"),
            List.of(),
            "",
            "open",
            "",
            1000,
            1,
            1,
            null,
            null,
            null,
            true,
            true,
            false,
            false,
            false,
            TimestampSource.from("system_nano", "/dev/null"),
            StreamRecordingWriter.PartitionGranularity.MINUTE
        );

        HistoricalBackfillSummary summary = service.run(config);

        assertEquals(2L, summary.restResponsesFetched());
        assertEquals(2L, summary.rawResponsesRecorded());
        assertEquals(4L, summary.canonicalEventsParsed());
        assertEquals(4L, summary.canonicalEventsRecorded());
        assertEquals(1L, summary.marketsDiscovered());
        try (var paths = Files.walk(tempDir)) {
            assertTrue(paths.anyMatch(path -> path.toString().contains("stream=canonical.trade") && path.toString().endsWith("events.ndjson")));
        }
    }

    private static final class FakeClient implements HistoricalBackfillClient {
        @Override
        public String getMarkets(RequestParameters params) {
            return """
                {"markets":[{"ticker":"M","market_id":"mid","status":"open","updated_time":"2026-05-08T00:00:00Z","last_price":56,"yes_bid":55,"yes_ask":57,"volume":10,"open_interest":20}],"cursor":""}
                """;
        }

        @Override
        public String getTrades(RequestParameters params) {
            return """
                {"trades":[{"trade_id":"t1","ticker":"M","count":10,"yes_price":56,"no_price":44,"taker_side":"yes","created_time":"2026-05-08T00:01:00Z"}],"cursor":""}
                """;
        }

        @Override
        public String getMarketOrderbook(String ticker, RequestParameters params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getMarketCandlesticks(String ticker, String seriesTicker, Integer startTs, Integer endTs, Integer periodInterval) {
            throw new UnsupportedOperationException();
        }
    }
}
