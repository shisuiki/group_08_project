package edu.illinois.group8.backfill;

import edu.illinois.group8.storage.db.AcceptedEventStore;
import edu.illinois.group8.storage.db.CanonicalDbEvent;
import edu.illinois.group8.storage.db.RawWsDbEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalBackfillServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void explicitRecordingTargetWritesCanonicalAndRawRestLayouts() throws Exception {
        BackendMetrics metrics = new BackendMetrics();
        HistoricalBackfillService service = new HistoricalBackfillService(
            new FakeClient(),
            new KalshiRestParser(),
            new RecordingCanonicalBackfillSink(new CanonicalRecordingWriter(
                tempDir,
                "canonical",
                TimestampSource.from("system_nano", "/dev/null"),
                metrics,
                StreamRecordingWriter.PartitionGranularity.MINUTE,
                "test_backfill",
                "backfill_metadata",
                "rest_fetch_ts_ns"
            )),
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
            HistoricalBackfillConfig.CanonicalTarget.RECORDING,
            "",
            "",
            "",
            true,
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
        try (var paths = Files.walk(tempDir)) {
            assertTrue(paths.anyMatch(path -> path.toString().contains("endpoint=rest_markets") && path.toString().endsWith("responses.ndjson")));
        }
    }

    @Test
    void dbTargetStoresCanonicalBatchAndDoesNotWriteFilesByDefault() throws Exception {
        CapturingAcceptedEventStore store = new CapturingAcceptedEventStore();
        HistoricalBackfillService service = new HistoricalBackfillService(
            new FakeClient(),
            new KalshiRestParser(),
            new DbCanonicalBackfillSink(store),
            null,
            new BackendMetrics()
        );
        HistoricalBackfillConfig config = baseConfig(
            HistoricalBackfillConfig.CanonicalTarget.DB,
            "jdbc:postgresql://db/kalshi",
            false
        );

        HistoricalBackfillSummary summary = service.run(config);

        assertEquals(2L, summary.restResponsesFetched());
        assertEquals(0L, summary.rawResponsesRecorded());
        assertEquals(4L, summary.canonicalEventsParsed());
        assertEquals(4L, summary.canonicalEventsRecorded());
        assertEquals(4, store.canonicalEvents.size());
        assertTrue(store.canonicalEvents.stream().anyMatch(event -> "canonical.trade".equals(event.streamName())));
        try (var paths = Files.walk(tempDir)) {
            assertEquals(1L, paths.count(), "default DB target must not create recording files");
        }
    }

    @Test
    void configDefaultsToDbAndRawRestDisabled() {
        HistoricalBackfillConfig config = HistoricalBackfillConfig.from(Map.of());

        assertEquals(HistoricalBackfillConfig.CanonicalTarget.DB, config.canonicalTarget());
        assertEquals("", config.dbUrl());
        assertEquals("", config.dbUser());
        assertEquals("", config.dbPassword());
        assertEquals(false, config.rawRestEnabled());
        assertEquals(Path.of("/app/recordings/raw-rest"), config.rawRestOutputRoot());
        assertNull(HistoricalBackfillCli.buildRawRestWriter(config));
    }

    @Test
    void dbTargetWithoutUrlFailsFastUnlessDryRun() {
        HistoricalBackfillConfig config = HistoricalBackfillConfig.from(Map.of());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, config::validate);

        assertTrue(thrown.getMessage().contains("HISTORICAL_BACKFILL_DB_URL"));
        assertTrue(thrown.getMessage().contains("DB_WRITER_DATABASE_URL"));

        HistoricalBackfillConfig dryRun = HistoricalBackfillConfig.from(Map.of("HISTORICAL_BACKFILL_DRY_RUN", "true"));
        dryRun.validate();
        assertNotNull(HistoricalBackfillCli.buildCanonicalSink(dryRun, new BackendMetrics()));
    }

    @Test
    void dbUrlPrefersHistoricalBackfillEnvAndFallsBackToWriterEnv() {
        HistoricalBackfillConfig fallback = HistoricalBackfillConfig.from(Map.of(
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://writer/kalshi",
            "DB_WRITER_DATABASE_USER", "writer",
            "DB_WRITER_DATABASE_PASSWORD", "writer-secret"
        ));
        assertEquals("jdbc:postgresql://writer/kalshi", fallback.dbUrl());
        assertEquals("writer", fallback.dbUser());
        assertEquals("writer-secret", fallback.dbPassword());

        HistoricalBackfillConfig override = HistoricalBackfillConfig.from(Map.of(
            "HISTORICAL_BACKFILL_DB_URL", "jdbc:postgresql://backfill/kalshi",
            "HISTORICAL_BACKFILL_DB_USER", "backfill",
            "HISTORICAL_BACKFILL_DB_PASSWORD", "backfill-secret",
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://writer/kalshi",
            "DB_WRITER_DATABASE_USER", "writer",
            "DB_WRITER_DATABASE_PASSWORD", "writer-secret"
        ));
        assertEquals("jdbc:postgresql://backfill/kalshi", override.dbUrl());
        assertEquals("backfill", override.dbUser());
        assertEquals("backfill-secret", override.dbPassword());
    }

    @Test
    void recordingTargetDoesNotRequireDatabaseUrlAndRawRestIsExplicit() {
        HistoricalBackfillConfig config = HistoricalBackfillConfig.from(Map.of(
            "HISTORICAL_BACKFILL_CANONICAL_TARGET", "recording",
            "HISTORICAL_BACKFILL_RAW_REST_ENABLED", "true",
            "HISTORICAL_BACKFILL_RAW_REST_ROOT", tempDir.resolve("raw-rest").toString()
        )).validate();

        assertEquals(HistoricalBackfillConfig.CanonicalTarget.RECORDING, config.canonicalTarget());
        assertInstanceOf(
            RecordingCanonicalBackfillSink.class,
            HistoricalBackfillCli.buildCanonicalSink(config, new BackendMetrics())
        );
        assertNotNull(HistoricalBackfillCli.buildRawRestWriter(config));
    }

    private HistoricalBackfillConfig baseConfig(
        HistoricalBackfillConfig.CanonicalTarget target,
        String dbUrl,
        boolean rawRestEnabled
    ) {
        return new HistoricalBackfillConfig(
            "https://api.elections.kalshi.com",
            "",
            "",
            tempDir,
            "canonical",
            target,
            dbUrl,
            "",
            "",
            rawRestEnabled,
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
        ).validate();
    }

    private static final class CapturingAcceptedEventStore implements AcceptedEventStore {
        private final List<CanonicalDbEvent> canonicalEvents = new ArrayList<>();

        @Override
        public void insertRawBatch(List<RawWsDbEvent> events) {
        }

        @Override
        public void insertCanonicalBatch(List<CanonicalDbEvent> events) {
            canonicalEvents.addAll(events);
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
