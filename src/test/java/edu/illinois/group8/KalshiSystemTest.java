package edu.illinois.group8;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.feature.CanonicalEnvelope;
import edu.illinois.group8.feature.CanonicalEnvelopeHandler;
import edu.illinois.group8.feature.CanonicalEnvelopeSource;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.CanonicalDbEvent;
import edu.illinois.group8.storage.db.DbOfferResult;
import edu.illinois.group8.storage.db.DbWriterConfig;
import edu.illinois.group8.storage.db.DbWriterStats;
import edu.illinois.group8.storage.db.RawDbIngestSink;
import edu.illinois.group8.storage.db.RawWsDbEventInput;
import edu.illinois.group8.wrapper.KalshiLiveWebSocketSession;
import edu.illinois.group8.wrapper.KalshiWrapper;
import edu.illinois.group8.wrapper.OrderBookRecoveryController;
import edu.illinois.group8.wrapper.OrderBookRecoveryController.RequestStatus;
import edu.illinois.group8.wrapper.OrderBookRecoveryGapConsumer;
import edu.illinois.group8.wrapper.OrderBookRecoveryMetrics;
import edu.illinois.group8.wrapper.RequestParameters;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void backendConfigDefaultsGapConsumerDisabledAndPrefersExternalAeronChannel() {
        BackendConfig config = BackendConfig.from(Map.of(
            "AERON_CHANNEL", "aeron:udp?endpoint=legacy:40456",
            "AERON_EXTERNAL_CHANNEL", "aeron:udp?endpoint=external:40456"
        ));

        assertFalse(config.orderBookRecoveryGapConsumerEnabled());
        assertEquals(64, config.orderBookRecoveryGapConsumerFragmentLimit());
        assertEquals(1, config.orderBookRecoveryGapConsumerIdleSleepMs());
        assertEquals("aeron:udp?endpoint=external:40456", config.aeronChannel());
    }

    @Test
    void backendConfigDefaultsReconnectEnabledAndParsesOverrides() {
        BackendConfig defaults = BackendConfig.from(Map.of());

        assertTrue(defaults.websocketReconnectEnabled());
        assertEquals(1000, defaults.websocketReconnectInitialBackoffMs());
        assertEquals(30000, defaults.websocketReconnectMaxBackoffMs());
        assertEquals(0, defaults.websocketReconnectMaxAttempts());

        BackendConfig overrides = BackendConfig.from(Map.of(
            "BACKEND_WS_RECONNECT_ENABLED", "false",
            "BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS", "10",
            "BACKEND_WS_RECONNECT_MAX_BACKOFF_MS", "50",
            "BACKEND_WS_RECONNECT_MAX_ATTEMPTS", "3"
        ));

        assertFalse(overrides.websocketReconnectEnabled());
        assertEquals(10, overrides.websocketReconnectInitialBackoffMs());
        assertEquals(50, overrides.websocketReconnectMaxBackoffMs());
        assertEquals(3, overrides.websocketReconnectMaxAttempts());
    }

    @Test
    void backendConfigRejectsInvalidReconnectValues() {
        IllegalStateException badInitialBackoff = assertThrows(
            IllegalStateException.class,
            () -> liveConfig(Map.of("BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS", "-1"))
                .validateForLiveIngestion()
        );
        assertTrue(badInitialBackoff.getMessage().contains("BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS"));

        IllegalStateException badMaxBackoff = assertThrows(
            IllegalStateException.class,
            () -> liveConfig(Map.of(
                "BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS", "100",
                "BACKEND_WS_RECONNECT_MAX_BACKOFF_MS", "99"
            )).validateForLiveIngestion()
        );
        assertTrue(badMaxBackoff.getMessage().contains("BACKEND_WS_RECONNECT_MAX_BACKOFF_MS"));

        IllegalStateException badMaxAttempts = assertThrows(
            IllegalStateException.class,
            () -> liveConfig(Map.of("BACKEND_WS_RECONNECT_MAX_ATTEMPTS", "-1"))
                .validateForLiveIngestion()
        );
        assertTrue(badMaxAttempts.getMessage().contains("BACKEND_WS_RECONNECT_MAX_ATTEMPTS"));
    }

    @Test
    void backendConfigDefaultsMetricsEndpointAndRejectsNegativePort() {
        BackendConfig config = BackendConfig.from(Map.of());

        assertEquals("0.0.0.0", config.metricsHost());
        assertEquals(8091, config.metricsPort());

        BackendConfig disabled = liveConfig(Map.of("BACKEND_METRICS_PORT", "0"));
        assertEquals(0, disabled.metricsPort());
        assertDoesNotThrow(disabled::validateForLiveIngestion);

        IllegalStateException negative = assertThrows(
            IllegalStateException.class,
            () -> liveConfig(Map.of("BACKEND_METRICS_PORT", "-1")).validateForLiveIngestion()
        );
        assertTrue(negative.getMessage().contains("BACKEND_METRICS_PORT"));
    }

    @Test
    void backendConfigRejectsInvalidGapConsumerValues() {
        IllegalStateException badFragmentLimit = assertThrows(
            IllegalStateException.class,
            () -> liveConfig(Map.of("BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_FRAGMENT_LIMIT", "0"))
                .validateForLiveIngestion()
        );
        assertTrue(badFragmentLimit.getMessage().contains("BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_FRAGMENT_LIMIT"));

        IllegalStateException badIdleSleep = assertThrows(
            IllegalStateException.class,
            () -> liveConfig(Map.of("BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_IDLE_SLEEP_MS", "-1"))
                .validateForLiveIngestion()
        );
        assertTrue(badIdleSleep.getMessage().contains("BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_IDLE_SLEEP_MS"));
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
        BackendMetrics sharedMetrics = new BackendMetrics();

        RawDbIngestSink sink = KalshiSystem.createRawDbIngestSink(config, sharedMetrics, (dbConfig, metrics) -> {
            factoryCalls.incrementAndGet();
            assertEquals(config, dbConfig);
            assertSame(sharedMetrics, metrics);
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
    void openMarketShardClientsUseFactoryWithoutSharedClusterArgument() {
        RawDbIngestSink sink = new RawDbIngestSink(new RecordingAsyncDbWriter(), "kalshi.websocket", "live");
        List<RawDbIngestSink.RawDbIngestConnection> connections = new ArrayList<>();
        AtomicInteger factoryCalls = new AtomicInteger();
        KalshiSystem.LiveWebSocketSessionFactory factory = (wrapper, rawDbConnection, recorder) -> {
            factoryCalls.incrementAndGet();
            connections.add(rawDbConnection);
            assertNull(recorder);
            return null;
        };

        assertNull(KalshiSystem.newOpenMarketWebSocketSession(null, sink, null, factory));
        assertNull(KalshiSystem.openOrderbookConnection(null, 2, sink, null, factory));

        assertEquals(2, factoryCalls.get());
        assertEquals(2, connections.size());
        assertEquals("live-1", connections.get(0).connectionId());
        assertEquals("live-2", connections.get(1).connectionId());
        assertFalse(connections.get(0) == connections.get(1));
    }

    @Test
    void orderBookRecoveryGapConsumerFactoryNoopsWhenDisabled() {
        BackendConfig config = BackendConfig.from(Map.of());
        AtomicInteger factoryCalls = new AtomicInteger();

        assertNull(KalshiSystem.createOrderBookRecoveryGapConsumer(config, null, (channel, streams) -> {
            factoryCalls.incrementAndGet();
            return new RecordingCanonicalEnvelopeSource();
        }));

        assertEquals(0, factoryCalls.get());
    }

    @Test
    void orderBookRecoveryGapConsumerFactoryUsesSequenceGapStreamAndConfiguredPollLimit() {
        BackendConfig config = BackendConfig.from(Map.of(
            "BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_ENABLED", "true",
            "BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_FRAGMENT_LIMIT", "7",
            "BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_IDLE_SLEEP_MS", "0",
            "AERON_EXTERNAL_CHANNEL", "aeron:udp?endpoint=external:40456"
        ));
        FakeExecutor executor = new FakeExecutor();
        BackendMetrics backendMetrics = new BackendMetrics();
        OrderBookRecoveryMetrics metrics = new OrderBookRecoveryMetrics(backendMetrics);
        OrderBookRecoveryController controller = new OrderBookRecoveryController(executor, 750, metrics);
        RecordingCanonicalEnvelopeSource source = new RecordingCanonicalEnvelopeSource(
            CanonicalEnvelope.fromPayload(
                EventType.SEQUENCE_GAP.streamName(),
                new JsonCanonicalSerializer().toJson(sequenceGap("UNKNOWN")),
                new JsonCanonicalSerializer().mapper()
            )
        );
        AtomicReference<List<StreamContract>> capturedStreams = new AtomicReference<>();
        AtomicReference<String> capturedChannel = new AtomicReference<>();

        OrderBookRecoveryGapConsumer consumer = KalshiSystem.createOrderBookRecoveryGapConsumer(
            config,
            controller,
            metrics,
            (channel, streams) -> {
                capturedChannel.set(channel);
                capturedStreams.set(streams);
                return source;
            }
        );

        assertNotNull(consumer);
        assertEquals("aeron:udp?endpoint=external:40456", capturedChannel.get());
        assertEquals(1, capturedStreams.get().size());
        assertEquals(EventType.SEQUENCE_GAP.streamName(), capturedStreams.get().get(0).streamName());

        assertEquals(1, consumer.pollOnce());

        assertEquals(7, source.lastFragmentLimit);
        assertEquals(1L, backendMetrics.get(
            "orderbook_recovery_consumer_polls_total",
            BackendMetrics.labels("service", "wsclient", "result", "non_empty")
        ));
        assertEquals(1L, backendMetrics.get(
            "orderbook_recovery_snapshot_request_decisions_total",
            BackendMetrics.labels("service", "wsclient", "status", "skipped_unknown_market")
        ));
        consumer.close();
    }

    @Test
    void orderBookRecoveryGapConsumerThreadIsDaemonAndNamed() {
        Thread thread = KalshiSystem.newOrderBookRecoveryGapConsumerThread(() -> {
        });

        assertTrue(thread.isDaemon());
        assertEquals("orderbook-recovery-gap-consumer", thread.getName());
    }

    @Test
    void metricsServerFactoryUsesConfiguredEndpointAndSharedMetrics() throws Exception {
        BackendConfig config = BackendConfig.from(Map.of(
            "BACKEND_METRICS_HOST", "127.0.0.1",
            "BACKEND_METRICS_PORT", "19091"
        ));
        BackendMetrics sharedMetrics = new BackendMetrics();
        AtomicReference<String> capturedHost = new AtomicReference<>();
        AtomicInteger capturedPort = new AtomicInteger();
        AtomicReference<BackendMetrics> capturedMetrics = new AtomicReference<>();
        AtomicInteger closeCalls = new AtomicInteger();

        AutoCloseable server = KalshiSystem.startMetricsServer(config, sharedMetrics, (host, port, metrics) -> {
            capturedHost.set(host);
            capturedPort.set(port);
            capturedMetrics.set(metrics);
            return closeCalls::incrementAndGet;
        });

        assertNotNull(server);
        assertEquals("127.0.0.1", capturedHost.get());
        assertEquals(19091, capturedPort.get());
        assertSame(sharedMetrics, capturedMetrics.get());

        server.close();

        assertEquals(1, closeCalls.get());
    }

    @Test
    void metricsServerFactoryNoopsWhenDisabled() {
        BackendConfig config = BackendConfig.from(Map.of("BACKEND_METRICS_PORT", "0"));
        AtomicInteger factoryCalls = new AtomicInteger();

        AutoCloseable server = KalshiSystem.startMetricsServer(config, new BackendMetrics(), (host, port, metrics) -> {
            factoryCalls.incrementAndGet();
            return () -> {
            };
        });

        assertNull(server);
        assertEquals(0, factoryCalls.get());
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

    @Test
    void configuredSessionSupervisorResubscribesAndReplacesRecoverySidAfterClose() throws Exception {
        BackendConfig config = liveConfig(Map.of(
            "KALSHI_WS_CHANNELS", "market_lifecycle_v2,orderbook_delta",
            "KALSHI_WS_SUBSCRIPTION_DELAY_MS", "0",
            "KALSHI_WS_ACK_TIMEOUT_MS", "500",
            "BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS", "0",
            "BACKEND_WS_RECONNECT_MAX_BACKOFF_MS", "0",
            "BACKEND_WS_RECONNECT_MAX_ATTEMPTS", "2"
        ));
        FakeExecutor executor = new FakeExecutor();
        OrderBookRecoveryController controller = new OrderBookRecoveryController(executor, 500);
        RecordingSessionFactory sessionFactory = new RecordingSessionFactory(10L, 20L);
        BackendMetrics metrics = new BackendMetrics();

        IllegalStateException exhausted = assertThrows(
            IllegalStateException.class,
            () -> KalshiSystem.runLiveSessionSupervisor(
                config,
                () -> KalshiSystem.startLiveSessionAttempt(
                    config,
                    new FakeKalshiWrapper(),
                    null,
                    null,
                    controller,
                    sessionFactory
                ),
                metrics,
                millis -> {
                }
            )
        );

        assertTrue(exhausted.getMessage().contains("BACKEND_WS_RECONNECT_MAX_ATTEMPTS"));
        assertEquals(2, sessionFactory.sessions.size());
        RecordingLiveWebSocketSession first = sessionFactory.sessions.get(0);
        RecordingLiveWebSocketSession second = sessionFactory.sessions.get(1);
        assertEquals(List.of(List.of("market_lifecycle_v2")), first.globalSubscriptions);
        assertEquals(List.of(List.of("market_lifecycle_v2")), second.globalSubscriptions);
        assertEquals(List.of(new MarketSubscription(List.of("orderbook_delta"), List.of("M1"))), first.marketSubscriptions);
        assertEquals(List.of(new MarketSubscription(List.of("orderbook_delta"), List.of("M1"))), second.marketSubscriptions);
        assertTrue(first.closeCalls > 0);
        assertTrue(second.closeCalls > 0);

        assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(sequenceGap("M1")));
        executor.runAll();

        assertTrue(first.snapshotCalls.isEmpty());
        assertEquals(1, second.snapshotCalls.size());
        assertSnapshotCall(second.snapshotCalls.get(0), 20L, "M1", 500);
        var labels = BackendMetrics.labels("service", "wsclient");
        assertEquals(2L, metrics.get("backend_ws_session_attempts_total", labels));
        assertEquals(2L, metrics.get("backend_ws_session_established_total", labels));
        assertEquals(2L, metrics.get("backend_ws_session_closes_total", labels));
        assertEquals(1L, metrics.get("backend_ws_session_retries_total", labels));
        assertEquals(1L, metrics.get(
            "backend_ws_session_failures_total",
            BackendMetrics.labels("service", "wsclient", "reason", "max_attempts_exhausted")
        ));
    }

    @Test
    void sessionSupervisorClosesFailedAttemptAndRetriesSubscriptionFailure() {
        BackendConfig config = liveConfig(Map.of(
            "KALSHI_WS_CHANNELS", "orderbook_delta",
            "KALSHI_WS_SUBSCRIPTION_DELAY_MS", "0",
            "BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS", "0",
            "BACKEND_WS_RECONNECT_MAX_BACKOFF_MS", "0",
            "BACKEND_WS_RECONNECT_MAX_ATTEMPTS", "2"
        ));
        FakeExecutor executor = new FakeExecutor();
        OrderBookRecoveryController controller = new OrderBookRecoveryController(executor, 750);
        RecordingSessionFactory sessionFactory = new RecordingSessionFactory(11L, 22L);
        sessionFactory.failSubscribeAttempts.add(1);
        BackendMetrics metrics = new BackendMetrics();

        IllegalStateException exhausted = assertThrows(
            IllegalStateException.class,
            () -> KalshiSystem.runLiveSessionSupervisor(
                config,
                () -> KalshiSystem.startLiveSessionAttempt(
                    config,
                    new FakeKalshiWrapper(),
                    null,
                    null,
                    controller,
                    sessionFactory
                ),
                metrics,
                millis -> {
                }
            )
        );

        assertTrue(exhausted.getMessage().contains("BACKEND_WS_RECONNECT_MAX_ATTEMPTS"));
        assertEquals(2, sessionFactory.sessions.size());
        assertEquals(1, sessionFactory.sessions.get(0).closeCalls);
        assertEquals(List.of(new MarketSubscription(List.of("orderbook_delta"), List.of("M1"))),
            sessionFactory.sessions.get(1).marketSubscriptions);
        assertEquals(1L, metrics.get(
            "backend_ws_session_failures_total",
            BackendMetrics.labels("service", "wsclient", "reason", "subscription")
        ));
    }

    @Test
    void openMarketSessionReconnectsDiscoverySubscriptionsAndRawConnections() {
        BackendConfig config = liveConfig(Map.ofEntries(
            Map.entry("KALSHI_MARKET_SELECTION_MODE", "open_markets"),
            Map.entry("KALSHI_MARKET_TICKERS", ""),
            Map.entry("KALSHI_WS_CHANNELS", "orderbook_delta"),
            Map.entry("KALSHI_ORDERBOOK_SUBSCRIPTION_CHUNK_SIZE", "2"),
            Map.entry("KALSHI_ORDERBOOK_MARKETS_PER_CONNECTION", "10000"),
            Map.entry("KALSHI_MARKET_DISCOVERY_MAX_MARKETS", "3"),
            Map.entry("KALSHI_WS_SUBSCRIPTION_DELAY_MS", "0"),
            Map.entry("KALSHI_WS_ACK_TIMEOUT_MS", "500"),
            Map.entry("BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS", "0"),
            Map.entry("BACKEND_WS_RECONNECT_MAX_BACKOFF_MS", "0"),
            Map.entry("BACKEND_WS_RECONNECT_MAX_ATTEMPTS", "2")
        ));
        RawDbIngestSink sink = new RawDbIngestSink(new RecordingAsyncDbWriter(), "kalshi.websocket", "live");
        FakeExecutor executor = new FakeExecutor();
        OrderBookRecoveryController controller = new OrderBookRecoveryController(executor, 500);
        RecordingSessionFactory sessionFactory = new RecordingSessionFactory(100L, 200L);

        assertThrows(
            IllegalStateException.class,
            () -> KalshiSystem.runLiveSessionSupervisor(
                config,
                () -> KalshiSystem.startLiveSessionAttempt(
                    config,
                    new FakeKalshiWrapper(marketsResponse("M1", "M2", "M3")),
                    sink,
                    null,
                    controller,
                    sessionFactory
                ),
                new BackendMetrics(),
                millis -> {
                }
            )
        );

        assertEquals(2, sessionFactory.sessions.size());
        assertEquals(List.of("live-1", "live-2"), sessionFactory.rawConnectionIds);
        for (RecordingLiveWebSocketSession session : sessionFactory.sessions) {
            assertEquals(List.of(new MarketSubscription(List.of("orderbook_delta"), List.of("M1", "M2"))),
                session.marketSubscriptions);
            assertEquals(List.of(new UpdateSubscription(session.sid, "add_markets", List.of("M3"))), session.updates);
        }

        assertEquals(RequestStatus.REQUEST_SCHEDULED, controller.handleGap(sequenceGap("M3")));
        executor.runAll();

        assertTrue(sessionFactory.sessions.get(0).snapshotCalls.isEmpty());
        assertEquals(1, sessionFactory.sessions.get(1).snapshotCalls.size());
        assertSnapshotCall(sessionFactory.sessions.get(1).snapshotCalls.get(0), 200L, "M3", 500);
    }

    private static BackendConfig liveConfig(Map<String, String> overrides) {
        Map<String, String> env = new HashMap<>();
        env.put("KALSHI_KEY_ID", "key");
        env.put("KALSHI_KEY_PATH", "/tmp/key.pem");
        env.put("KALSHI_MARKET_TICKERS", "M1");
        env.putAll(overrides);
        return BackendConfig.from(env);
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

    private static String marketsResponse(String... tickers) {
        StringBuilder markets = new StringBuilder();
        for (int index = 0; index < tickers.length; index++) {
            if (index > 0) {
                markets.append(',');
            }
            markets.append("{\"ticker\":\"").append(tickers[index]).append("\"}");
        }
        return "{\"markets\":[" + markets + "],\"cursor\":\"\"}";
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

    private static final class RecordingCanonicalEnvelopeSource implements CanonicalEnvelopeSource {
        private final ArrayDeque<CanonicalEnvelope> envelopes = new ArrayDeque<>();
        private int lastFragmentLimit;

        private RecordingCanonicalEnvelopeSource(CanonicalEnvelope... envelopes) {
            this.envelopes.addAll(List.of(envelopes));
        }

        @Override
        public int poll(CanonicalEnvelopeHandler handler, int fragmentLimit) {
            lastFragmentLimit = fragmentLimit;
            int fragments = 0;
            while (fragments < fragmentLimit && !envelopes.isEmpty()) {
                handler.onEvent(envelopes.removeFirst());
                fragments++;
            }
            return fragments;
        }
    }

    private record SnapshotCall(long sid, String[] marketTickers, int timeoutMs) {
    }

    private record MarketSubscription(List<String> channels, List<String> marketTickers) {
        private MarketSubscription(String[] channels, String[] marketTickers) {
            this(List.of(channels), List.of(marketTickers));
        }
    }

    private record UpdateSubscription(long sid, String action, List<String> marketTickers) {
        private UpdateSubscription(long sid, String action, String[] marketTickers) {
            this(sid, action, List.of(marketTickers));
        }
    }

    private static final class RecordingSnapshotRequester implements OrderBookRecoveryController.SnapshotRequester {
        private final List<SnapshotCall> calls = new ArrayList<>();

        @Override
        public void requestSnapshotAndAwaitOk(long sid, String[] marketTickers, int timeoutMs) {
            calls.add(new SnapshotCall(sid, marketTickers, timeoutMs));
        }
    }

    private static final class FakeKalshiWrapper extends KalshiWrapper {
        private final String marketsResponse;

        private FakeKalshiWrapper() {
            this("{\"markets\":[],\"cursor\":\"\"}");
        }

        private FakeKalshiWrapper(String marketsResponse) {
            super("https://example.test", "", "");
            this.marketsResponse = marketsResponse;
        }

        @Override
        public String getMarkets(RequestParameters params) {
            return marketsResponse;
        }
    }

    private static final class RecordingSessionFactory implements KalshiSystem.LiveWebSocketSessionFactory {
        private final ArrayDeque<Long> sids = new ArrayDeque<>();
        private final List<RecordingLiveWebSocketSession> sessions = new ArrayList<>();
        private final List<String> rawConnectionIds = new ArrayList<>();
        private final List<Integer> failSubscribeAttempts = new ArrayList<>();
        private int createCalls;

        private RecordingSessionFactory(Long... sids) {
            this.sids.addAll(List.of(sids));
        }

        @Override
        public KalshiLiveWebSocketSession create(
            KalshiWrapper wrapper,
            RawDbIngestSink.RawDbIngestConnection rawDbConnection,
            edu.illinois.group8.recorder.RawIngestRecorder rawIngestRecorder
        ) {
            createCalls++;
            if (rawDbConnection != null) {
                rawConnectionIds.add(rawDbConnection.connectionId());
            }
            long sid = sids.isEmpty() ? createCalls : sids.removeFirst();
            RecordingLiveWebSocketSession session = new RecordingLiveWebSocketSession(
                sid,
                failSubscribeAttempts.contains(createCalls)
            );
            sessions.add(session);
            return session;
        }
    }

    private static final class RecordingLiveWebSocketSession implements KalshiLiveWebSocketSession {
        private final long sid;
        private final boolean failSubscribeAndAwait;
        private final CompletableFuture<Void> closed = CompletableFuture.completedFuture(null);
        private final List<List<String>> globalSubscriptions = new ArrayList<>();
        private final List<MarketSubscription> marketSubscriptions = new ArrayList<>();
        private final List<UpdateSubscription> updates = new ArrayList<>();
        private final List<SnapshotCall> snapshotCalls = new ArrayList<>();
        private int closeCalls;

        private RecordingLiveWebSocketSession(long sid, boolean failSubscribeAndAwait) {
            this.sid = sid;
            this.failSubscribeAndAwait = failSubscribeAndAwait;
        }

        @Override
        public boolean subscribe(String[] channels) {
            globalSubscriptions.add(List.of(channels));
            return true;
        }

        @Override
        public boolean subscribe(String[] channels, String[] marketTickers) {
            marketSubscriptions.add(new MarketSubscription(channels, marketTickers));
            return true;
        }

        @Override
        public long subscribeAndAwaitSid(String[] channels, String[] marketTickers, int timeoutMs) {
            if (failSubscribeAndAwait) {
                throw new IllegalStateException("subscribe failed");
            }
            marketSubscriptions.add(new MarketSubscription(channels, marketTickers));
            return sid;
        }

        @Override
        public void updateAndAwaitOk(long subscriptionId, String action, String[] marketTickers, int timeoutMs) {
            updates.add(new UpdateSubscription(subscriptionId, action, marketTickers));
        }

        @Override
        public void requestSnapshotAndAwaitOk(long subscriptionId, String[] marketTickers, int timeoutMs) {
            snapshotCalls.add(new SnapshotCall(subscriptionId, marketTickers, timeoutMs));
        }

        @Override
        public CompletionStage<Void> closed() {
            return closed;
        }

        @Override
        public boolean isClosed() {
            return closed.isDone();
        }

        @Override
        public void close() {
            closeCalls++;
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
