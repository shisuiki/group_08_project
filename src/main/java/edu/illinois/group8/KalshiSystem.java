package edu.illinois.group8;

import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.cluster.ClientClusterOrchestrator;
import edu.illinois.group8.feature.AeronCanonicalEnvelopeSource;
import edu.illinois.group8.feature.CanonicalEnvelopeSource;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.recorder.RawIngestRecorder;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.AsyncDbWriterFactory;
import edu.illinois.group8.storage.db.DbWriterConfig;
import edu.illinois.group8.storage.db.RawDbIngestSink;
import edu.illinois.group8.wrapper.KalshiWebSocketClient;
import edu.illinois.group8.wrapper.OrderBookRecoveryController;
import edu.illinois.group8.wrapper.OrderBookRecoveryGapConsumer;
import edu.illinois.group8.wrapper.OrderBookRecoveryGapHandler;
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

        RawDbIngestSink rawDbSink = createRawDbIngestSink(DbWriterConfig.fromEnvironment());
        registerRawDbShutdownHook(rawDbSink);
        RawIngestRecorder rawIngestRecorder = createRawIngestRecorder(config);
        ExecutorService orderBookRecoveryExecutor = newOrderBookRecoveryExecutor();
        registerOrderBookRecoveryShutdownHook(orderBookRecoveryExecutor);
        OrderBookRecoveryController orderBookRecoveryController = new OrderBookRecoveryController(
            orderBookRecoveryExecutor,
            config.subscriptionAckTimeoutMs()
        );
        OrderBookRecoveryGapConsumer orderBookRecoveryGapConsumer = createOrderBookRecoveryGapConsumer(
            config,
            orderBookRecoveryController,
            KalshiSystem::newOrderBookRecoveryGapSource
        );
        Thread orderBookRecoveryGapConsumerThread = startOrderBookRecoveryGapConsumer(orderBookRecoveryGapConsumer);
        registerOrderBookRecoveryGapConsumerShutdownHook(
            orderBookRecoveryGapConsumer,
            orderBookRecoveryGapConsumerThread
        );

        KalshiWrapper wrapper = new KalshiWrapper(config.kalshiBaseUrl(), config.kalshiKeyId(), config.kalshiKeyPath());
        try {
            if (config.openMarketSelectionEnabled()) {
                ClientClusterOrchestrator cluster = new ClientClusterOrchestrator(config.clusterAddresses(), config.hostIp());
                KalshiWebSocketClient wsClient = newWebSocketClient(
                    wrapper,
                    cluster,
                    newRawDbConnection(rawDbSink),
                    rawIngestRecorder
                );
                subscribeOpenMarketCapture(
                    config,
                    wrapper,
                    wsClient,
                    cluster,
                    rawDbSink,
                    rawIngestRecorder,
                    orderBookRecoveryController
                );
            } else {
                List<String> tickers = resolveMarketTickers(config, wrapper);
                if (tickers.isEmpty()) {
                    throw new IllegalStateException("No market tickers resolved for live subscription.");
                }
                KalshiWebSocketClient wsClient = newWebSocketClient(
                    wrapper,
                    newRawDbConnection(rawDbSink),
                    rawIngestRecorder
                );
                subscribeConfiguredMarketCapture(config, wsClient, tickers, orderBookRecoveryController);
            }
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending Kalshi subscription commands.", exc);
        }
    }

    private static void subscribeOpenMarketCapture(
        BackendConfig config,
        KalshiWrapper wrapper,
        KalshiWebSocketClient wsClient,
        ClientClusterOrchestrator cluster,
        RawDbIngestSink rawDbSink,
        RawIngestRecorder rawIngestRecorder,
        OrderBookRecoveryController orderBookRecoveryController
    ) throws InterruptedException {
        List<String> globalChannels = config.websocketGlobalChannels().isEmpty()
            ? requestedOpenMarketGlobalChannels(config.websocketChannels())
            : unique(config.websocketGlobalChannels());
        List<String> filteredChannels = config.websocketFilteredChannels().isEmpty()
            ? requestedFilteredChannels(config.websocketChannels(), globalChannels)
            : unique(config.websocketFilteredChannels());

        if (!globalChannels.isEmpty()) {
            System.out.println("Subscribing Kalshi global channels " + globalChannels + " for all markets.");
            wsClient.subscribe(globalChannels.toArray(new String[0]));
            delayBetweenSubscriptions(config);
        }
        discoverAndSubscribeMarketChunks(
            config,
            wrapper,
            cluster,
            wsClient,
            filteredChannels,
            "",
            rawDbSink,
            rawIngestRecorder,
            orderBookRecoveryController
        );
    }

    private static void subscribeConfiguredMarketCapture(
        BackendConfig config,
        KalshiWebSocketClient wsClient,
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
            wsClient.subscribe(globalChannels.toArray(new String[0]));
            delayBetweenSubscriptions(config);
        }
        subscribeMarketChunks(config, wsClient, filteredChannels, tickers, orderBookRecoveryController);
    }

    private static void subscribeMarketChunks(
        BackendConfig config,
        KalshiWebSocketClient wsClient,
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
                wsClient.subscribe(channelArray, marketArray);
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
        ClientClusterOrchestrator cluster,
        KalshiWebSocketClient initialClient,
        List<String> channels,
        String seriesTicker,
        RawDbIngestSink rawDbSink,
        RawIngestRecorder rawIngestRecorder,
        OrderBookRecoveryController orderBookRecoveryController
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
        KalshiWebSocketClient orderbookClient = initialClient;
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
                            cluster,
                            connectionIndex,
                            rawDbSink,
                            rawIngestRecorder
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
                                cluster,
                                connectionIndex,
                                rawDbSink,
                                rawIngestRecorder
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
                            cluster,
                            connectionIndex,
                            rawDbSink,
                            rawIngestRecorder
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

    private static KalshiWebSocketClient openOrderbookConnection(
        KalshiWrapper wrapper,
        ClientClusterOrchestrator cluster,
        int connectionIndex,
        RawDbIngestSink rawDbSink,
        RawIngestRecorder rawIngestRecorder
    ) {
        System.out.println("Opening Kalshi orderbook websocket shard " + connectionIndex + ".");
        return newWebSocketClient(wrapper, cluster, newRawDbConnection(rawDbSink), rawIngestRecorder);
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

    private static KalshiWebSocketClient newWebSocketClient(
        KalshiWrapper wrapper,
        RawDbIngestSink.RawDbIngestConnection rawDbConnection,
        RawIngestRecorder rawIngestRecorder
    ) {
        if (rawIngestRecorder == null) {
            return new KalshiWebSocketClient(wrapper, rawDbConnection);
        }
        return KalshiWebSocketClient.recordingCapture(wrapper, rawDbConnection, rawIngestRecorder);
    }

    private static KalshiWebSocketClient newWebSocketClient(
        KalshiWrapper wrapper,
        ClientClusterOrchestrator cluster,
        RawDbIngestSink.RawDbIngestConnection rawDbConnection,
        RawIngestRecorder rawIngestRecorder
    ) {
        if (rawIngestRecorder == null) {
            return new KalshiWebSocketClient(wrapper, cluster, rawDbConnection);
        }
        return KalshiWebSocketClient.recordingCapture(wrapper, cluster, rawDbConnection, rawIngestRecorder);
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

    static OrderBookRecoveryGapConsumer createOrderBookRecoveryGapConsumer(
        BackendConfig config,
        OrderBookRecoveryController controller,
        OrderBookRecoveryGapSourceFactory sourceFactory
    ) {
        Objects.requireNonNull(config, "config");
        if (!config.orderBookRecoveryGapConsumerEnabled()) {
            return null;
        }
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(sourceFactory, "sourceFactory");
        List<StreamContract> streams = List.of(StreamRegistry.byName(EventType.SEQUENCE_GAP.streamName())
            .orElseThrow(() -> new IllegalStateException("system.sequence_gaps stream is not registered.")));
        CanonicalEnvelopeSource source = sourceFactory.create(config.aeronChannel(), streams);
        return new OrderBookRecoveryGapConsumer(
            source,
            new OrderBookRecoveryGapHandler(controller),
            config.orderBookRecoveryGapConsumerFragmentLimit(),
            config.orderBookRecoveryGapConsumerIdleSleepMs()
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
        KalshiWebSocketClient wsClient,
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
