package edu.illinois.group8.cluster;

import edu.illinois.group8.book.OrderBookRecoveryCheckpoint;
import edu.illinois.group8.book.OrderBookStateManager;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.MarketTrade;
import edu.illinois.group8.esb.DataProcessor;
import edu.illinois.group8.esb.DataProcessorRecoveryState;
import edu.illinois.group8.ingress.KalshiIngressEnvelope;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import edu.illinois.group8.publication.CollectingEventPublisher;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.CanonicalDbEvent;
import edu.illinois.group8.storage.db.DbOfferResult;
import edu.illinois.group8.storage.db.DbWriterConfig;
import edu.illinois.group8.storage.db.DbWriterStats;
import edu.illinois.group8.storage.db.RawWsDbEventInput;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.aeron.cluster.service.Cluster.Role;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ESBClusteredServiceTest {
    @Test
    void initializesCanonicalDbSinkFromConfigFactoryAndClosesItOnceOnTerminate() {
        DbWriterConfig config = DbWriterConfig.from(java.util.Map.of());
        BackendMetrics metrics = new BackendMetrics();
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        AtomicReference<DbWriterConfig> seenConfig = new AtomicReference<>();
        AtomicReference<BackendMetrics> seenMetrics = new AtomicReference<>();
        ESBClusteredService service = new ESBClusteredService(
            "aeron-dir",
            "localhost",
            () -> config,
            (providedConfig, providedMetrics) -> {
                seenConfig.set(providedConfig);
                seenMetrics.set(providedMetrics);
                return writer;
            }
        );

        service.initializeCanonicalDbSink(metrics);
        service.onTerminate(null);
        service.onTerminate(null);

        assertSame(config, seenConfig.get());
        assertSame(metrics, seenMetrics.get());
        assertEquals(1, writer.closeCalls);
    }

    @Test
    void leaderMessageScratchDoesNotLeakTrailingBytesAcrossMessages() {
        CollectingEventPublisher publisher = new CollectingEventPublisher();
        BackendMetrics metrics = new BackendMetrics();
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            metrics
        );
        ESBClusteredService service = new ESBClusteredService("aeron-dir", "localhost", processor);
        service.onRoleChange(Role.LEADER);
        byte[] first = KalshiIngressEnvelope.wrapBytes(
            tradeMessage("first-long-trade-id", "MARKET-LONG"),
            111L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "connection-with-long-id",
            null
        );
        byte[] second = KalshiIngressEnvelope.wrapBytes(
            tradeMessage("b", "S"),
            222L,
            Instant.parse("2026-05-08T00:00:01Z"),
            "c",
            null
        );
        assertTrue(first.length > second.length);
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[first.length]);

        buffer.putBytes(0, first);
        service.onSessionMessage(null, 0L, buffer, 0, first.length, null);
        buffer.putBytes(0, second);
        service.onSessionMessage(null, 0L, buffer, 0, second.length, null);

        MarketTrade firstTrade = assertInstanceOf(MarketTrade.class, publisher.events().get(1));
        MarketTrade secondTrade = assertInstanceOf(MarketTrade.class, publisher.events().get(3));
        assertEquals("MARKET-LONG", firstTrade.metadata().marketTicker());
        assertEquals(111L, firstTrade.metadata().ingestTsNs());
        assertEquals("S", secondTrade.metadata().marketTicker());
        assertEquals(222L, secondTrade.metadata().ingestTsNs());
        assertEquals(first.length + second.length, metrics.get(
            "backend_ws_bytes_total",
            BackendMetrics.labels("service", "backend", "source", "kalshi")
        ));
    }

    @Test
    void snapshotPayloadRoundTripWithoutAeron() {
        DataProcessorRecoveryState state = new DataProcessorRecoveryState(
            Map.of(11L, 4L),
            List.of(new OrderBookRecoveryCheckpoint("M", 2L))
        );
        DataProcessor sourceProcessor = testProcessor();
        sourceProcessor.restoreRecoveryState(state);
        ESBClusteredService sourceService = new ESBClusteredService("aeron-dir", "localhost", sourceProcessor);

        byte[] payload = sourceService.snapshotPayloadBytes();

        DataProcessor restoredProcessor = testProcessor();
        ESBClusteredService restoredService = new ESBClusteredService(
            "aeron-dir",
            "localhost",
            restoredProcessor
        );
        restoredService.restoreSnapshotPayload(payload);

        assertEquals(state, restoredProcessor.recoveryState());
    }

    @Test
    void restoreSnapshotPayloadRejectsMalformedPayload() {
        ESBClusteredService service = new ESBClusteredService("aeron-dir", "localhost", testProcessor());

        assertThrows(IllegalArgumentException.class, () ->
            service.restoreSnapshotPayload("not-json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void snapshotPayloadRequiresProcessor() {
        ESBClusteredService service = new ESBClusteredService("aeron-dir", "localhost");

        assertThrows(IllegalStateException.class, service::snapshotPayloadBytes);
    }

    @Test
    void restorePayloadRequiresProcessor() {
        ESBClusteredService service = new ESBClusteredService("aeron-dir", "localhost");

        assertThrows(IllegalStateException.class, () -> service.restoreSnapshotPayload(new byte[] {'{', '}'}));
    }

    private static String tradeMessage(String tradeId, String marketTicker) {
        return "{\"type\":\"trade\",\"sid\":11,\"msg\":{\"trade_id\":\"" + tradeId
            + "\",\"market_ticker\":\"" + marketTicker
            + "\",\"yes_price_dollars\":\"0.360\",\"no_price_dollars\":\"0.640\","
            + "\"count_fp\":\"1.00\",\"taker_side\":\"yes\",\"ts_ms\":1669149841000}}";
    }

    private static DataProcessor testProcessor() {
        return new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            new CollectingEventPublisher(),
            new BackendMetrics()
        );
    }

    private static final class RecordingAsyncDbWriter implements AsyncDbWriter {
        private int closeCalls;

        @Override
        public DbOfferResult offerRaw(RawWsDbEventInput input) {
            throw new UnsupportedOperationException("raw writes are out of scope");
        }

        @Override
        public DbOfferResult offerCanonical(CanonicalDbEvent event) {
            throw new UnsupportedOperationException("pre-mapped canonical writes are out of scope");
        }

        @Override
        public DbOfferResult offerCanonicalEvent(CanonicalEvent event) {
            return DbOfferResult.ACCEPTED;
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
