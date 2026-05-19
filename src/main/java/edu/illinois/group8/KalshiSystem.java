package edu.illinois.group8;

import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.feature.AeronCanonicalEnvelopeSource;
import edu.illinois.group8.feature.CanonicalEnvelopeSource;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.recorder.RawIngestRecorder;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.AsyncDbWriterFactory;
import edu.illinois.group8.storage.db.DbWriterConfig;
import edu.illinois.group8.storage.db.RawDbIngestSink;
import edu.illinois.group8.wrapper.KalshiLiveWebSocketSession;
import edu.illinois.group8.wrapper.KalshiWebSocketClient;
import edu.illinois.group8.wrapper.OrderBookRecoveryController;
import edu.illinois.group8.wrapper.OrderBookRecoveryGapConsumer;
import edu.illinois.group8.wrapper.OrderBookRecoveryGapHandler;
import edu.illinois.group8.wrapper.OrderBookRecoveryMetrics;
import edu.illinois.group8.wrapper.KalshiWrapper;
import edu.illinois.group8.wrapper.RequestParameters;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class KalshiSystem {
    private static final Set<String> OPEN_MARKET_GLOBAL_CHANNELS = Set.of(
        "ticker",
        "trade",
        "market_lifecycle_v2"
    );
    private static final Set<String> FILTER_UNSUPPORTED_CHANNELS = Set.of("market_lifecycle_v2");

    public static void main(String[] args) {
        BackendConfig config = BackendConfig.fromEnvironment();
        config.validateForLiveIngestion();
        if (config.websocketChannels().isEmpty()) {
            throw new IllegalStateException("KALSHI_WS_CHANNELS must contain at least one channel.");
        }

        BackendMetrics backendMetrics = new BackendMetrics();
        AutoCloseable metricsServer = startMetricsServer(config, backendMetrics, KalshiMetricsServer::start);
        registerMetricsShutdownHook(metricsServer);
        RawDbIngestSink rawDbSink = createRawDbIngestSink(
            DbWriterConfig.fromEnvironment(),
            backendMetrics,
            AsyncDbWriterFactory::create
        );
        registerRawDbShutdownHook(rawDbSink);
        RawIngestRecorder rawIngestRecorder = createRawIngestRecorder(config);
        OrderBookRecoveryMetrics orderBookRecoveryMetrics = new OrderBookRecoveryMetrics(backendMetrics);
        ExecutorService orderBookRecoveryExecutor = newOrderBookRecoveryExecutor();
        registerOrderBookRecoveryShutdownHook(orderBookRecoveryExecutor);
        OrderBookRecoveryController orderBookRecoveryController = new OrderBookRecoveryController(
            orderBookRecoveryExecutor,
            config.subscriptionAckTimeoutMs(),
            orderBookRecoveryMetrics
        );
        OrderBookRecoveryGapConsumer orderBookRecoveryGapConsumer = createOrderBookRecoveryGapConsumer(
            config,
            orderBookRecoveryController,
            orderBookRecoveryMetrics,
            KalshiSystem::newOrderBookRecoveryGapSource
        );
        Thread orderBookRecoveryGapConsumerThread = startOrderBookRecoveryGapConsumer(orderBookRecoveryGapConsumer);
        registerOrderBookRecoveryGapConsumerShutdownHook(
            orderBookRecoveryGapConsumer,
            orderBookRecoveryGapConsumerThread
        );

        KalshiWrapper wrapper = new KalshiWrapper(config.kalshiBaseUrl(), config.kalshiKeyId(), config.kalshiKeyPath());
        try {
            LiveSessionAttemptFactory attemptFactory = () -> startLiveSessionAttempt(
                config,
                wrapper,
                rawDbSink,
                rawIngestRecorder,
                orderBookRecoveryController,
                KalshiSystem::newWebSocketSession
            );
            if (config.websocketReconnectEnabled()) {
                runLiveSessionSupervisor(config, attemptFactory, backendMetrics, Thread::sleep);
            } else {
                attemptFactory.start();
            }
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending Kalshi subscription commands.", exc);
        }
    }

    static LiveSessionHandle startLiveSessionAttempt(
        BackendConfig config,
        KalshiWrapper wrapper,
        RawDbIngestSink rawDbSink,
        RawIngestRecorder rawIngestRecorder,
        OrderBookRecoveryController orderBookRecoveryController,
        LiveWebSocketSessionFactory sessionFactory
    ) throws InterruptedException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(wrapper, "wrapper");
        Objects.requireNonNull(sessionFactory, "sessionFactory");
        List<KalshiLiveWebSocketSession> sessions = new ArrayList<>();
        LiveWebSocketSessionFactory trackingFactory = (sessionWrapper, rawDbConnection, recorder) -> {
            KalshiLiveWebSocketSession session = sessionFactory.create(sessionWrapper, rawDbConnection, recorder);
            sessions.add(Objects.requireNonNull(session, "sessionFactory result"));
            return session;
        };
        try {
            if (config.openMarketSelectionEnabled()) {
                KalshiLiveWebSocketSession wsClient = newOpenMarketWebSocketSession(
                    wrapper,
                    rawDbSink,
                    rawIngestRecorder,
                    trackingFactory
                );
                subscribeOpenMarketCapture(
                    config,
                    wrapper,
                    wsClient,
                    rawDbSink,
                    rawIngestRecorder,
                    orderBookRecoveryController,
                    trackingFactory
                );
            } else {
                List<String> tickers = resolveMarketTickers(config, wrapper);
                if (tickers.isEmpty()) {
                    throw new IllegalStateException("No market tickers resolved for live subscription.");
                }
                KalshiLiveWebSocketSession wsClient = newWebSocketSession(
                    wrapper,
                    newRawDbConnection(rawDbSink),
                    rawIngestRecorder,
                    trackingFactory
                );
                subscribeConfiguredMarketCapture(config, wsClient, tickers, orderBookRecoveryController);
            }
            return new LiveSessionHandle(sessions);
        } catch (InterruptedException | RuntimeException exc) {
            closeSessions(sessions, exc);
            throw exc;
        }
    }

    static void runLiveSessionSupervisor(
        BackendConfig config,
        LiveSessionAttemptFactory attemptFactory,
        BackendMetrics metrics,
        Sleeper sleeper
    ) throws InterruptedException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(attemptFactory, "attemptFactory");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(sleeper, "sleeper");
        WsReconnectMetricHandles handles = wsReconnectMetricHandles(metrics);
        int attempts = 0;
        int maxAttempts = config.websocketReconnectMaxAttempts();
        long backoffMs = config.websocketReconnectInitialBackoffMs();
        while (maxAttempts == 0 || attempts < maxAttempts) {
            attempts++;
            LiveSessionHandle session = null;
            handles.attempts().increment();
            try {
                session = attemptFactory.start();
                handles.established().increment();
                waitForSessionClose(session);
                handles.closes().increment();
            } catch (InterruptedException exc) {
                if (session != null) {
                    session.close();
                }
                throw exc;
            } catch (RuntimeException exc) {
                handles.subscriptionFailures().increment();
                if (session != null) {
                    session.close();
                }
            }

            if (session != null) {
                session.close();
            }
            if (maxAttempts != 0 && attempts >= maxAttempts) {
                handles.maxAttemptExhaustions().increment();
                throw new IllegalStateException("BACKEND_WS_RECONNECT_MAX_ATTEMPTS exhausted after " + attempts + " attempts.");
            }
            handles.retries().increment();
            if (backoffMs > 0) {
                sleeper.sleep(backoffMs);
            }
            backoffMs = nextReconnectBackoffMs(backoffMs, config.websocketReconnectMaxBackoffMs());
        }
    }

    private static void waitForSessionClose(LiveSessionHandle session) throws InterruptedException {
        try {
            session.closed().toCompletableFuture().get();
        } catch (ExecutionException exc) {
            throw new IllegalStateException("Live websocket session close signal failed.", exc.getCause());
        }
    }

    private static long nextReconnectBackoffMs(long currentBackoffMs, long maxBackoffMs) {
        if (currentBackoffMs <= 0) {
            return maxBackoffMs <= 0 ? 0 : 1;
        }
        long doubled = currentBackoffMs > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : currentBackoffMs * 2;
        return Math.min(doubled, maxBackoffMs);
    }

    private static WsReconnectMetricHandles wsReconnectMetricHandles(BackendMetrics metrics) {
        var labels = BackendMetrics.labels("service", "wsclient");
        return new WsReconnectMetricHandles(
            metrics.counter("backend_ws_session_attempts_total", labels),
            metrics.counter("backend_ws_session_established_total", labels),
            metrics.counter("backend_ws_session_closes_total", labels),
            metrics.counter("backend_ws_session_retries_total", labels),
            metrics.counter(
                "backend_ws_session_failures_total",
                BackendMetrics.labels("service", "wsclient", "reason", "subscription")
            ),
            metrics.counter(
                "backend_ws_session_failures_total",
                BackendMetrics.labels("service", "wsclient", "reason", "max_attempts_exhausted")
            )
        );
    }

    private static void subscribeGlobalOrThrow(KalshiLiveWebSocketSession session, List<String> channels) {
        if (!session.subscribe(channels.toArray(new String[0]))) {
            throw new IllegalStateException("Kalshi websocket failed to send global subscribe for channels " + channels + ".");
        }
    }

    private static void subscribeMarketsOrThrow(
        KalshiLiveWebSocketSession session,
        String[] channels,
        String[] marketTickers
    ) {
        if (!session.subscribe(channels, marketTickers)) {
            throw new IllegalStateException("Kalshi websocket failed to send market subscribe.");
        }
    }

    private static void closeSessions(List<KalshiLiveWebSocketSession> sessions) {
        closeSessions(sessions, null);
    }

    private static void closeSessions(List<KalshiLiveWebSocketSession> sessions, Throwable primary) {
        RuntimeException failure = null;
        for (KalshiLiveWebSocketSession session : sessions) {
            try {
                session.close();
            } catch (RuntimeException exc) {
                if (failure == null) {
                    failure = exc;
                } else {
                    failure.addSuppressed(exc);
                }
            }
        }
        if (failure != null) {
            if (primary != null) {
                primary.addSuppressed(failure);
            } else {
                throw failure;
            }
        }
    }

    static final class LiveSessionHandle implements AutoCloseable {
        private final List<KalshiLiveWebSocketSession> sessions;
        private final java.util.concurrent.CompletableFuture<Void> closed = new java.util.concurrent.CompletableFuture<>();

        LiveSessionHandle(List<KalshiLiveWebSocketSession> sessions) {
            if (sessions == null || sessions.isEmpty()) {
                throw new IllegalArgumentException("Live session attempt must create at least one websocket session.");
            }
            this.sessions = List.copyOf(sessions);
            for (KalshiLiveWebSocketSession session : this.sessions) {
                session.closed().whenComplete((ignored, failure) -> closed.complete(null));
            }
        }

        CompletionStage<Void> closed() {
            return closed;
        }

        int sessionCount() {
            return sessions.size();
        }

        @Override
        public void close() {
            closeSessions(sessions);
        }
    }

    @FunctionalInterface
    interface LiveSessionAttemptFactory {
        LiveSessionHandle start() throws InterruptedException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    record WsReconnectMetricHandles(
        BackendMetrics.Counter attempts,
        BackendMetrics.Counter established,
        BackendMetrics.Counter closes,
        BackendMetrics.Counter retries,
        BackendMetrics.Counter subscriptionFailures,
        BackendMetrics.Counter maxAttemptExhaustions
    ) {
    }

    private static void subscribeOpenMarketCapture(
        BackendConfig config,
        KalshiWrapper wrapper,
        KalshiLiveWebSocketSession wsClient,
        RawDbIngestSink rawDbSink,
        RawIngestRecorder rawIngestRecorder,
        OrderBookRecoveryController orderBookRecoveryController,
        LiveWebSocketSessionFactory clientFactory
    ) throws InterruptedException {
        List<String> globalChannels = config.websocketGlobalChannels().isEmpty()
            ? requestedOpenMarketGlobalChannels(config.websocketChannels())
            : unique(config.websocketGlobalChannels());
        List<String> filteredChannels = config.websocketFilteredChannels().isEmpty()
            ? requestedFilteredChannels(config.websocketChannels(), globalChannels)
            : unique(config.websocketFilteredChannels());

        if (!globalChannels.isEmpty()) {
            System.out.println("Subscribing Kalshi global channels " + globalChannels + " for all markets.");
            subscribeGlobalOrThrow(wsClient, globalChannels);
            delayBetweenSubscriptions(config);
        }
        discoverAndSubscribeMarketChunks(
            config,
            wrapper,
            wsClient,
            filteredChannels,
            "",
            rawDbSink,
            rawIngestRecorder,
            orderBookRecoveryController,
            clientFactory
        );
    }

    private static void subscribeConfiguredMarketCapture(
        BackendConfig config,
        KalshiLiveWebSocketSession wsClient,
        List<String> tickers,
        OrderBookRecoveryController orderBookRecoveryController
    ) throws InterruptedException {
        List<String> globalChannels = config.websocketChannels().stream()
            .filter(FILTER_UNSUPPORTED_CHANNELS::contains)
            .toList();
        List<String> filteredChannels = config.websocketChannels().stream()
            .filter(channel -> !FILTER_UNSUPPORTED_CHANNELS.contains(channel))
            .toList();

        if (!globalChannels.isEmpty()) {
            System.out.println("Subscribing Kalshi unfiltered channels " + globalChannels + ".");
            subscribeGlobalOrThrow(wsClient, globalChannels);
            delayBetweenSubscriptions(config);
        }
        subscribeMarketChunks(config, wsClient, filteredChannels, tickers, orderBookRecoveryController);
    }

    private static void subscribeMarketChunks(
        BackendConfig config,
        KalshiLiveWebSocketSession wsClient,
        List<String> channels,
        List<String> tickers,
        OrderBookRecoveryController orderBookRecoveryController
    ) throws InterruptedException {
        if (channels.isEmpty()) {
            return;
        }
        int chunkSize = config.orderbookSubscriptionChunkSize();
        int subscriptionCount = (tickers.size() + chunkSize - 1) / chunkSize;
        for (int start = 0, subscriptionIndex = 1; start < tickers.size(); start += chunkSize, subscriptionIndex++) {
            int end = Math.min(tickers.size(), start + chunkSize);
            List<String> chunk = tickers.subList(start, end);
            System.out.println(
                "Subscribing Kalshi channels " + channels
                    + " for markets " + start + "-" + (end - 1)
                    + " of " + tickers.size()
                    + " (subscription " + subscriptionIndex + "/" + subscriptionCount + ")."
            );
            String[] channelArray = channels.toArray(new String[0]);
            String[] marketArray = chunk.toArray(new String[0]);
            if (orderBookRecoveryController == null) {
                subscribeMarketsOrThrow(wsClient, channelArray, marketArray);
            } else {
                long sid = wsClient.subscribeAndAwaitSid(channelArray, marketArray, config.subscriptionAckTimeoutMs());
                registerRecoveryMarkets(orderBookRecoveryController, sid, chunk, wsClient::requestSnapshotAndAwaitOk);
            }
            if (end < tickers.size()) {
                delayBetweenSubscriptions(config);
            }
        }
    }

    private static void discoverAndSubscribeMarketChunks(
        BackendConfig config,
        KalshiWrapper wrapper,
        KalshiLiveWebSocketSession initialClient,
        List<String> channels,
        String seriesTicker,
        RawDbIngestSink rawDbSink,
        RawIngestRecorder rawIngestRecorder,
        OrderBookRecoveryController orderBookRecoveryController,
        LiveWebSocketSessionFactory clientFactory
    ) throws InterruptedException {
        if (channels.isEmpty()) {
            return;
        }

        LinkedHashSet<String> seenTickers = new LinkedHashSet<>();
        List<String> chunk = new ArrayList<>(config.orderbookSubscriptionChunkSize());
        String cursor = "";
        int page = 1;
        int subscribed = 0;
        int subscribedOnCurrentConnection = 0;
        int connectionIndex = 1;
        long subscriptionSid = -1;
        KalshiLiveWebSocketSession orderbookClient = initialClient;
        System.out.println("Using Kalshi websocket shard 1 for filtered orderbook subscriptions.");
        while (true) {
            String marketsStr = requestMarketDiscoveryPage(config, wrapper, seriesTicker, cursor, page);
            List<String> pageTickers = parseMarketTickers(marketsStr);
            for (String ticker : pageTickers) {
                if (!seenTickers.add(ticker)) {
                    continue;
                }
                chunk.add(ticker);
                if (chunk.size() >= config.orderbookSubscriptionChunkSize()) {
                    if (subscribedOnCurrentConnection >= config.orderbookMarketsPerConnection()) {
                        connectionIndex++;
                        subscribedOnCurrentConnection = 0;
                        subscriptionSid = -1;
                        orderbookClient = openOrderbookConnection(
                            wrapper,
                            connectionIndex,
                            rawDbSink,
                            rawIngestRecorder,
                            clientFactory
                        );
                    }
                    OrderbookSubscriptionState state = subscribeDiscoveredChunk(
                        config,
                        orderbookClient,
                        channels,
                        chunk,
                        subscribed,
                        subscriptionSid,
                        orderBookRecoveryController
                    );
                    subscribed = state.totalSubscribed();
                    subscriptionSid = state.sid();
                    subscribedOnCurrentConnection += chunk.size();
                    chunk.clear();
                }
                if (config.marketDiscoveryMaxMarkets() > 0 && seenTickers.size() >= config.marketDiscoveryMaxMarkets()) {
                    if (!chunk.isEmpty()) {
                        if (subscribedOnCurrentConnection >= config.orderbookMarketsPerConnection()) {
                            connectionIndex++;
                            subscribedOnCurrentConnection = 0;
                            subscriptionSid = -1;
                            orderbookClient = openOrderbookConnection(
                                wrapper,
                                connectionIndex,
                                rawDbSink,
                                rawIngestRecorder,
                                clientFactory
                            );
                        }
                        OrderbookSubscriptionState state = subscribeDiscoveredChunk(
                            config,
                            orderbookClient,
                            channels,
                            chunk,
                            subscribed,
                            subscriptionSid,
                            orderBookRecoveryController
                        );
                        subscribed = state.totalSubscribed();
                        subscriptionSid = state.sid();
                    }
                    System.out.println("Completed bounded Kalshi market discovery with " + seenTickers.size()
                        + " unique tickers and " + subscribed + " acknowledged filtered subscriptions.");
                    return;
                }
            }

            System.out.println(
                "Kalshi market discovery page " + page
                    + " returned " + pageTickers.size()
                    + " markets; discovered " + seenTickers.size()
                    + " unique tickers and subscribed " + subscribed + " so far."
            );

            cursor = parseCursor(marketsStr);
            if (isBlank(cursor)) {
                if (!chunk.isEmpty()) {
                    if (subscribedOnCurrentConnection >= config.orderbookMarketsPerConnection()) {
                        connectionIndex++;
                        subscribedOnCurrentConnection = 0;
                        subscriptionSid = -1;
                        orderbookClient = openOrderbookConnection(
                            wrapper,
                            connectionIndex,
                            rawDbSink,
                            rawIngestRecorder,
                            clientFactory
                        );
                    }
                    OrderbookSubscriptionState state = subscribeDiscoveredChunk(
                        config,
                        orderbookClient,
                        channels,
                        chunk,
                        subscribed,
                        subscriptionSid,
                        orderBookRecoveryController
                    );
                    subscribed = state.totalSubscribed();
                    subscriptionSid = state.sid();
                }
                System.out.println("Completed Kalshi market discovery with " + seenTickers.size()
                    + " unique tickers and " + subscribed + " acknowledged filtered subscriptions.");
                return;
            }
            page++;
        }
    }

    static KalshiLiveWebSocketSession openOrderbookConnection(
        KalshiWrapper wrapper,
        int connectionIndex,
        RawDbIngestSink rawDbSink,
        RawIngestRecorder rawIngestRecorder,
        LiveWebSocketSessionFactory clientFactory
    ) {
        System.out.println("Opening Kalshi orderbook websocket shard " + connectionIndex + ".");
        return newOpenMarketWebSocketSession(wrapper, rawDbSink, rawIngestRecorder, clientFactory);
    }

    static KalshiLiveWebSocketSession newOpenMarketWebSocketSession(
        KalshiWrapper wrapper,
        RawDbIngestSink rawDbSink,
        RawIngestRecorder rawIngestRecorder,
        LiveWebSocketSessionFactory clientFactory
    ) {
        Objects.requireNonNull(clientFactory, "clientFactory");
        return clientFactory.create(wrapper, newRawDbConnection(rawDbSink), rawIngestRecorder);
    }

    static KalshiLiveWebSocketSession newWebSocketSession(
        KalshiWrapper wrapper,
        RawDbIngestSink.RawDbIngestConnection rawDbConnection,
        RawIngestRecorder rawIngestRecorder,
        LiveWebSocketSessionFactory clientFactory
    ) {
        Objects.requireNonNull(clientFactory, "clientFactory");
        return clientFactory.create(wrapper, rawDbConnection, rawIngestRecorder);
    }

    static RawIngestRecorder createRawIngestRecorder(BackendConfig config) {
        return createRawIngestRecorder(config, RawIngestRecorder::fromEnvironment);
    }

    static RawIngestRecorder createRawIngestRecorder(
        BackendConfig config,
        Supplier<RawIngestRecorder> recorderFactory
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(recorderFactory, "recorderFactory");
        if (!config.recordingCaptureProfileEnabled()) {
            return null;
        }
        return recorderFactory.get();
    }

    private static KalshiLiveWebSocketSession newWebSocketSession(
        KalshiWrapper wrapper,
        RawDbIngestSink.RawDbIngestConnection rawDbConnection,
        RawIngestRecorder rawIngestRecorder
    ) {
        if (rawIngestRecorder == null) {
            return new KalshiWebSocketClient(wrapper, rawDbConnection);
        }
        return KalshiWebSocketClient.recordingCapture(wrapper, rawDbConnection, rawIngestRecorder);
    }

    @FunctionalInterface
    interface LiveWebSocketSessionFactory {
        KalshiLiveWebSocketSession create(
            KalshiWrapper wrapper,
            RawDbIngestSink.RawDbIngestConnection rawDbConnection,
            RawIngestRecorder rawIngestRecorder
        );
    }

    static RawDbIngestSink createRawDbIngestSink(DbWriterConfig config) {
        return createRawDbIngestSink(config, new BackendMetrics(), AsyncDbWriterFactory::create);
    }

    static RawDbIngestSink createRawDbIngestSink(
        DbWriterConfig config,
        BackendMetrics metrics,
        BiFunction<DbWriterConfig, BackendMetrics, AsyncDbWriter> writerFactory
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(metrics, "metrics");
        if (!config.enabled()) {
            return null;
        }
        AsyncDbWriter writer = Objects.requireNonNull(writerFactory, "writerFactory").apply(config, metrics);
        return new RawDbIngestSink(writer, config.rawSource(), config.rawCaptureId());
    }

    static AutoCloseable startMetricsServer(
        BackendConfig config,
        BackendMetrics metrics,
        MetricsServerFactory serverFactory
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(serverFactory, "serverFactory");
        if (config.metricsPort() == 0) {
            return null;
        }
        if (config.metricsPort() < 0) {
            throw new IllegalStateException("BACKEND_METRICS_PORT must be zero or positive.");
        }
        return serverFactory.start(config.metricsHost(), config.metricsPort(), metrics);
    }

    @FunctionalInterface
    interface MetricsServerFactory {
        AutoCloseable start(String host, int port, BackendMetrics metrics);
    }

    static OrderBookRecoveryGapConsumer createOrderBookRecoveryGapConsumer(
        BackendConfig config,
        OrderBookRecoveryController controller,
        OrderBookRecoveryGapSourceFactory sourceFactory
    ) {
        return createOrderBookRecoveryGapConsumer(
            config,
            controller,
            new OrderBookRecoveryMetrics(new BackendMetrics()),
            sourceFactory
        );
    }

    static OrderBookRecoveryGapConsumer createOrderBookRecoveryGapConsumer(
        BackendConfig config,
        OrderBookRecoveryController controller,
        OrderBookRecoveryMetrics metrics,
        OrderBookRecoveryGapSourceFactory sourceFactory
    ) {
        Objects.requireNonNull(config, "config");
        if (!config.orderBookRecoveryGapConsumerEnabled()) {
            return null;
        }
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(sourceFactory, "sourceFactory");
        List<StreamContract> streams = List.of(StreamRegistry.byName(EventType.SEQUENCE_GAP.streamName())
            .orElseThrow(() -> new IllegalStateException("system.sequence_gaps stream is not registered.")));
        CanonicalEnvelopeSource source = sourceFactory.create(config.aeronChannel(), streams);
        return new OrderBookRecoveryGapConsumer(
            source,
            new OrderBookRecoveryGapHandler(controller, metrics),
            config.orderBookRecoveryGapConsumerFragmentLimit(),
            config.orderBookRecoveryGapConsumerIdleSleepMs(),
            metrics
        );
    }

    private static CanonicalEnvelopeSource newOrderBookRecoveryGapSource(
        String aeronChannel,
        List<StreamContract> streams
    ) {
        return new AeronCanonicalEnvelopeSource(aeronChannel, streams);
    }

    @FunctionalInterface
    interface OrderBookRecoveryGapSourceFactory {
        CanonicalEnvelopeSource create(String aeronChannel, List<StreamContract> streams);
    }

    private static RawDbIngestSink.RawDbIngestConnection newRawDbConnection(RawDbIngestSink rawDbSink) {
        return rawDbSink == null ? null : rawDbSink.newConnection();
    }

    private static void registerRawDbShutdownHook(RawDbIngestSink rawDbSink) {
        if (rawDbSink != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(rawDbSink::close, "raw-db-ingest-sink-shutdown"));
        }
    }

    private static void registerMetricsShutdownHook(AutoCloseable metricsServer) {
        if (metricsServer != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(
                () -> closeMetricsServer(metricsServer),
                "backend-metrics-server-shutdown"
            ));
        }
    }

    private static void closeMetricsServer(AutoCloseable metricsServer) {
        try {
            metricsServer.close();
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to stop backend metrics server.", exc);
        }
    }

    private static ExecutorService newOrderBookRecoveryExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "orderbook-recovery-snapshot-requester");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void registerOrderBookRecoveryShutdownHook(ExecutorService executor) {
        Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdownNow, "orderbook-recovery-shutdown"));
    }

    static Thread newOrderBookRecoveryGapConsumerThread(Runnable target) {
        Thread thread = new Thread(Objects.requireNonNull(target, "target"), "orderbook-recovery-gap-consumer");
        thread.setDaemon(true);
        return thread;
    }

    private static Thread startOrderBookRecoveryGapConsumer(OrderBookRecoveryGapConsumer consumer) {
        if (consumer == null) {
            return null;
        }
        Thread thread = newOrderBookRecoveryGapConsumerThread(consumer::runUntilStopped);
        thread.start();
        return thread;
    }

    private static void registerOrderBookRecoveryGapConsumerShutdownHook(
        OrderBookRecoveryGapConsumer consumer,
        Thread thread
    ) {
        if (consumer == null || thread == null) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.close();
            thread.interrupt();
        }, "orderbook-recovery-gap-consumer-shutdown"));
    }

    private static OrderbookSubscriptionState subscribeDiscoveredChunk(
        BackendConfig config,
        KalshiLiveWebSocketSession wsClient,
        List<String> channels,
        List<String> chunk,
        int alreadySubscribed,
        long currentSid,
        OrderBookRecoveryController orderBookRecoveryController
    ) throws InterruptedException {
        int totalAfterChunk = alreadySubscribed + chunk.size();
        System.out.println(
            "Subscribing Kalshi channels " + channels
                + " for " + chunk.size()
                + " discovered markets; target_total_subscribed=" + totalAfterChunk + "."
        );
        String[] channelArray = channels.toArray(new String[0]);
        String[] marketArray = chunk.toArray(new String[0]);
        long sid = currentSid;
        if (sid < 0) {
            sid = wsClient.subscribeAndAwaitSid(channelArray, marketArray, config.subscriptionAckTimeoutMs());
            System.out.println("Kalshi subscription sid=" + sid + " acknowledged for initial chunk of " + chunk.size() + " markets.");
        } else {
            wsClient.updateAndAwaitOk(sid, "add_markets", marketArray, config.subscriptionAckTimeoutMs());
            System.out.println("Kalshi subscription sid=" + sid + " acknowledged add_markets for " + chunk.size() + " markets.");
        }
        registerRecoveryMarkets(orderBookRecoveryController, sid, chunk, wsClient::requestSnapshotAndAwaitOk);
        delayBetweenSubscriptions(config);
        return new OrderbookSubscriptionState(sid, totalAfterChunk);
    }

    static void registerRecoveryMarkets(
        OrderBookRecoveryController orderBookRecoveryController,
        long sid,
        List<String> chunk,
        OrderBookRecoveryController.SnapshotRequester requester
    ) {
        if (orderBookRecoveryController == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        for (String marketTicker : chunk) {
            orderBookRecoveryController.registerMarket(marketTicker, sid, requester);
        }
    }

    private static List<String> requestedOpenMarketGlobalChannels(List<String> requestedChannels) {
        return requestedChannels.stream()
            .filter(OPEN_MARKET_GLOBAL_CHANNELS::contains)
            .toList();
    }

    private static List<String> requestedFilteredChannels(List<String> requestedChannels, List<String> globalChannels) {
        Set<String> global = Set.copyOf(globalChannels);
        return requestedChannels.stream()
            .filter(channel -> !global.contains(channel))
            .toList();
    }

    private static List<String> resolveMarketTickers(BackendConfig config, KalshiWrapper wrapper) {
        if (!config.openMarketSelectionEnabled() && !config.marketTickers().isEmpty()) {
            return config.marketTickers();
        }

        String seriesTicker = config.openMarketSelectionEnabled() ? "" : config.marketSeriesTicker();
        List<String> tickers = fetchMarketTickers(config, wrapper, seriesTicker);
        String selection = config.openMarketSelectionEnabled()
            ? "all " + config.marketStatus() + " markets"
            : config.marketStatus() + " markets in series " + seriesTicker;
        System.out.println("Resolved " + tickers.size() + " Kalshi market tickers from " + selection + ".");
        return tickers;
    }

    private static List<String> fetchMarketTickers(
        BackendConfig config,
        KalshiWrapper wrapper,
        String seriesTicker
    ) {
        LinkedHashSet<String> tickers = new LinkedHashSet<>();
        String cursor = "";
        int page = 1;
        while (true) {
            String marketsStr = requestMarketDiscoveryPage(config, wrapper, seriesTicker, cursor, page);

            List<String> pageTickers = parseMarketTickers(marketsStr);
            for (String ticker : pageTickers) {
                tickers.add(ticker);
                if (config.marketDiscoveryMaxMarkets() > 0 && tickers.size() >= config.marketDiscoveryMaxMarkets()) {
                    return new ArrayList<>(tickers);
                }
            }
            System.out.println(
                "Kalshi market discovery page " + page
                    + " returned " + pageTickers.size()
                    + " markets; resolved " + tickers.size() + " unique tickers so far."
            );

            cursor = parseCursor(marketsStr);
            if (isBlank(cursor)) {
                return new ArrayList<>(tickers);
            }
            page++;
        }
    }

    private static String requestMarketDiscoveryPage(
        BackendConfig config,
        KalshiWrapper wrapper,
        String seriesTicker,
        String cursor,
        int page
    ) {
        RequestParameters params = new RequestParameters();
        params.addParam("limit", config.marketDiscoveryLimit());
        if (!isBlank(config.marketStatus())) {
            params.addParam("status", config.marketStatus());
        }
        if (!isBlank(config.marketMveFilter())) {
            params.addParam("mve_filter", config.marketMveFilter());
        }
        if (!isBlank(seriesTicker)) {
            params.addParam("series_ticker", seriesTicker);
        }
        if (!isBlank(cursor)) {
            params.addParam("cursor", cursor);
        }

        String marketsStr = wrapper.getMarkets(params);
        if (marketsStr == null) {
            throw new IllegalStateException("Kalshi markets request returned null on discovery page " + page + ".");
        }
        return marketsStr;
    }

    static List<String> parseMarketTickers(String marketsStr) {
        JSONObject data = parseJsonObject(marketsStr);
        Object marketsObj = data.get("markets");
        if (!(marketsObj instanceof JSONArray markets)) {
            return List.of();
        }

        List<String> tickers = new ArrayList<>();
        for (Object marketObj : markets) {
            if (!(marketObj instanceof JSONObject market)) {
                continue;
            }
            String ticker = (String) market.get("ticker");
            if (!isBlank(ticker)) {
                tickers.add(ticker);
            }
        }
        return tickers;
    }

    static String parseCursor(String marketsStr) {
        Object cursor = parseJsonObject(marketsStr).get("cursor");
        return cursor instanceof String ? (String) cursor : "";
    }

    private static JSONObject parseJsonObject(String raw) {
        JSONParser parser = new JSONParser();
        try {
            return (JSONObject) parser.parse(raw);
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to parse Kalshi markets response", exc);
        }
    }

    private static List<String> unique(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static void delayBetweenSubscriptions(BackendConfig config) throws InterruptedException {
        if (config.subscriptionDelayMs() > 0) {
            Thread.sleep(config.subscriptionDelayMs());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record OrderbookSubscriptionState(long sid, int totalSubscribed) {
    }
}
