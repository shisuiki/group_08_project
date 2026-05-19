package edu.illinois.group8;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.CanonicalDbEvent;
import edu.illinois.group8.storage.db.DbOfferResult;
import edu.illinois.group8.storage.db.DbWriterConfig;
import edu.illinois.group8.storage.db.DbWriterStats;
import edu.illinois.group8.storage.db.RawDbIngestSink;
import edu.illinois.group8.storage.db.RawWsDbEventInput;
import edu.illinois.group8.wrapper.OrderBookRecoveryController;
import edu.illinois.group8.wrapper.OrderBookRecoveryController.RequestStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KalshiSystemTest {
    @Test
    void parsesPaginatedMarketDiscoveryResponse() {
        String response = """
            {
              "cursor": "next-page",
              "markets": [
                {"ticker": "KXONE-26MAY03-Y"},
                {"ticker": "KXTWO-26MAY03-N"},
                {"ticker": ""}
              ]
            }
            """;

        assertEquals(
            java.util.List.of("KXONE-26MAY03-Y", "KXTWO-26MAY03-N"),
            KalshiSystem.parseMarketTickers(response)
        );
        assertEquals("next-page", KalshiSystem.parseCursor(response));
    }

    @Test
    void openMarketModeDoesNotRequireSeriesOrExplicitTickers() {
        BackendConfig config = BackendConfig.from(Map.of(
            "KALSHI_KEY_ID", "key",
            "KALSHI_KEY_PATH", "/tmp/key.pem",
            "KALSHI_MARKET_SELECTION_MODE", "open_markets"
        ));

        assertTrue(config.openMarketSelectionEnabled());
        assertFalse(config.sourceSequenceMonitorEnabled());
        assertTrue(config.orderBookDerivedEnabled());
        assertDoesNotThrow(config::validateForLiveIngestion);
    }

    @Test
    void recordingCaptureProfileIsExplicit() {
        BackendConfig config = BackendConfig.from(Map.of(
            "BACKEND_PROFILE", BackendConfig.PROFILE_RECORDING_CAPTURE
        ));

        assertTrue(config.recordingCaptureProfileEnabled());
    }

    @Test
    void rawIngestRecorderFactoryOnlyRunsForRecordingCaptureProfile() {
        AtomicInteger factoryCalls = new AtomicInteger();
        BackendConfig liveConfig = BackendConfig.from(Map.of(
            "BACKEND_PROFILE", BackendConfig.PROFILE_DOCKER
        ));

        assertNull(KalshiSystem.createRawIngestRecorder(liveConfig, () -> {
            factoryCalls.incrementAndGet();
            return null;
        }));
        assertEquals(0, factoryCalls.get());

        BackendConfig captureConfig = BackendConfig.from(Map.of(
            "BACKEND_PROFILE", BackendConfig.PROFILE_RECORDING_CAPTURE
        ));
        assertNull(KalshiSystem.createRawIngestRecorder(captureConfig, () -> {
            factoryCalls.incrementAndGet();
            return null;
        }));
        assertEquals(1, factoryCalls.get());
    }

    @Test
    void rawDbSinkFactoryDoesNotCreateWriterWhenDisabled() {
        DbWriterConfig config = DbWriterConfig.from(Map.of());
        AtomicInteger factoryCalls = new AtomicInteger();

        RawDbIngestSink sink = KalshiSystem.createRawDbIngestSink(config, new BackendMetrics(), (dbConfig, metrics) -> {
            factoryCalls.incrementAndGet();
            return new RecordingAsyncDbWriter();
        });

        assertNull(sink);
        assertEquals(0, factoryCalls.get());
    }

    @Test
    void rawDbSinkFactoryAutoEnablesWriterWhenDatabaseUrlIsPresent() {
        DbWriterConfig config = DbWriterConfig.from(Map.of(
            DbWriterConfig.DATABASE_URL_ENV, "jdbc:postgresql://localhost/kalshi_test"
        ));
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        AtomicInteger factoryCalls = new AtomicInteger();

        RawDbIngestSink sink = KalshiSystem.createRawDbIngestSink(config, new BackendMetrics(), (dbConfig, metrics) -> {
            factoryCalls.incrementAndGet();
            assertEquals(config, dbConfig);
            return writer;
        });

        assertNotNull(sink);
        assertEquals(1, factoryCalls.get());
    }

    @Test
    void rawDbSinkFactoryHonorsExplicitFalseOptOutWithDatabaseUrl() {
        DbWriterConfig config = DbWriterConfig.from(Map.of(
            DbWriterConfig.ENABLED_ENV, "false",
            DbWriterConfig.DATABASE_URL_ENV, "jdbc:postgresql://localhost/kalshi_test"
        ));
        AtomicInteger factoryCalls = new AtomicInteger();

        RawDbIngestSink sink = KalshiSystem.createRawDbIngestSink(config, new BackendMetrics(), (dbConfig, metrics) -> {
            factoryCalls.incrementAndGet();
            return new RecordingAsyncDbWriter();
        });

        assertNull(sink);
        assertEquals(0, factoryCalls.get());
    }

    @Test
    void rawDbSinkFactoryUsesConfiguredSourceCaptureAndCanClose() {
        DbWriterConfig config = new DbWriterConfig(
            true,
            "jdbc:postgresql://localhost/kalshi_test",
            "",
            "",
            "kalshi.custom",
            "capture-custom",
            8,
            2
        );
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        AtomicInteger factoryCalls = new AtomicInteger();

        RawDbIngestSink sink = KalshiSystem.createRawDbIngestSink(config, new BackendMetrics(), (dbConfig, metrics) -> {
            factoryCalls.incrementAndGet();
            assertEquals(config, dbConfig);
            assertNotNull(metrics);
            return writer;
        });

        assertNotNull(sink);
        assertEquals(1, factoryCalls.get());
        sink.newConnection().recordInbound("payload", 123L, Instant.parse("2026-05-19T00:00:00Z"));
        RawWsDbEventInput input = writer.rawInputs.get(0);
        assertEquals("kalshi.custom", input.source());
        assertEquals("capture-custom", input.captureId());
        assertEquals("payload", input.rawPayload());

        sink.close();

        assertEquals(1, writer.closeCalls);
    }

    @Test
    void registerRecoveryMarketsRegistersMultipleMarketsAndSchedulesSnapshots() {
        FakeExecutor executor = new FakeExecutor();
        OrderBookRecoveryController controller = new OrderBookRecoveryController(executor, 750);
        RecordingSnapshotRequester requester = new RecordingSnapshotRequester();

        KalshiSystem.registerRecoveryMarkets(controller, 42L, List.of("M1", "M2"), requester);

        assertEquals(RequestStatus.SKIPPED_UNKNOWN_MARKET, controller.handleGap(sequenceGap("UNKNOWN")));
        assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(sequenceGap("M1")));
        assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(sequenceGap("M2")));
        assertEquals(2, executor.pendingCount());
        assertTrue(requester.calls.isEmpty());

        executor.runAll();

        assertEquals(2, requester.calls.size());
        assertSnapshotCall(requester.calls.get(0), 42L, "M1", 750);
        assertSnapshotCall(requester.calls.get(1), 42L, "M2", 750);
    }

    @Test
    void registerRecoveryMarketsNoopsForNullControllerOrEmptyChunk() {
        FakeExecutor executor = new FakeExecutor();
        OrderBookRecoveryController controller = new OrderBookRecoveryController(executor, 750);
        RecordingSnapshotRequester requester = new RecordingSnapshotRequester();

        assertDoesNotThrow(() -> KalshiSystem.registerRecoveryMarkets(null, 42L, List.of("M1"), requester));
        assertDoesNotThrow(() -> KalshiSystem.registerRecoveryMarkets(controller, 42L, List.of(), requester));

        assertEquals(RequestStatus.SKIPPED_UNKNOWN_MARKET, controller.handleGap(sequenceGap("M1")));
        assertEquals(0, executor.pendingCount());
        assertTrue(requester.calls.isEmpty());
    }

    private static void assertSnapshotCall(SnapshotCall call, long sid, String marketTicker, int timeoutMs) {
        assertEquals(sid, call.sid());
        assertArrayEquals(new String[] {marketTicker}, call.marketTickers());
        assertEquals(timeoutMs, call.timeoutMs());
    }

    private static SequenceGapEvent sequenceGap(String marketTicker) {
        return new SequenceGapEvent(
            "gap-" + marketTicker,
            new EventMetadata(
                "kalshi",
                "orderbook_delta",
                42L,
                4L,
                marketTicker,
                null,
                1L,
                100L,
                null,
                "raw-1",
                null
            ),
            3L,
            4L,
            "crossed_book",
            "pause_market_and_request_fresh_snapshot"
        );
    }

    private static final class FakeExecutor implements java.util.concurrent.Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private int pendingCount() {
            return tasks.size();
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }

    private record SnapshotCall(long sid, String[] marketTickers, int timeoutMs) {
    }

    private static final class RecordingSnapshotRequester implements OrderBookRecoveryController.SnapshotRequester {
        private final List<SnapshotCall> calls = new ArrayList<>();

        @Override
        public void requestSnapshotAndAwaitOk(long sid, String[] marketTickers, int timeoutMs) {
            calls.add(new SnapshotCall(sid, marketTickers, timeoutMs));
        }
    }

    private static final class RecordingAsyncDbWriter implements AsyncDbWriter {
        private final List<RawWsDbEventInput> rawInputs = new ArrayList<>();
        private int closeCalls;

        @Override
        public DbOfferResult offerRaw(RawWsDbEventInput input) {
            rawInputs.add(input);
            return DbOfferResult.ACCEPTED;
        }

        @Override
        public DbOfferResult offerCanonical(CanonicalDbEvent event) {
            throw new UnsupportedOperationException("canonical writes are out of scope");
        }

        @Override
        public DbOfferResult offerCanonicalEvent(CanonicalEvent event) {
            throw new UnsupportedOperationException("canonical writes are out of scope");
        }

        @Override
        public DbWriterStats stats() {
            return DbWriterStats.empty();
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
