package edu.illinois.group8;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.CanonicalDbEvent;
import edu.illinois.group8.storage.db.DbOfferResult;
import edu.illinois.group8.storage.db.DbWriterConfig;
import edu.illinois.group8.storage.db.DbWriterStats;
import edu.illinois.group8.storage.db.RawDbIngestSink;
import edu.illinois.group8.storage.db.RawWsDbEventInput;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
