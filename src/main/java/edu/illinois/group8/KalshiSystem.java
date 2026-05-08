package edu.illinois.group8;

import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.cluster.ClientClusterOrchestrator;
import edu.illinois.group8.wrapper.KalshiWebSocketClient;
import edu.illinois.group8.wrapper.KalshiWrapper;
import edu.illinois.group8.wrapper.RequestParameters;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

        KalshiWrapper wrapper = new KalshiWrapper(config.kalshiBaseUrl(), config.kalshiKeyId(), config.kalshiKeyPath());
        try {
            if (config.openMarketSelectionEnabled()) {
                ClientClusterOrchestrator cluster = new ClientClusterOrchestrator(config.clusterAddresses(), config.hostIp());
                KalshiWebSocketClient wsClient = new KalshiWebSocketClient(wrapper, cluster);
                subscribeOpenMarketCapture(config, wrapper, wsClient, cluster);
            } else {
                List<String> tickers = resolveMarketTickers(config, wrapper);
                if (tickers.isEmpty()) {
                    throw new IllegalStateException("No market tickers resolved for live subscription.");
                }
                KalshiWebSocketClient wsClient = new KalshiWebSocketClient(wrapper);
                subscribeConfiguredMarketCapture(config, wsClient, tickers);
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
        ClientClusterOrchestrator cluster
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
        discoverAndSubscribeMarketChunks(config, wrapper, cluster, wsClient, filteredChannels, "");
    }

    private static void subscribeConfiguredMarketCapture(
        BackendConfig config,
        KalshiWebSocketClient wsClient,
        List<String> tickers
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
        subscribeMarketChunks(config, wsClient, filteredChannels, tickers);
    }

    private static void subscribeMarketChunks(
        BackendConfig config,
        KalshiWebSocketClient wsClient,
        List<String> channels,
        List<String> tickers
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
            wsClient.subscribe(channels.toArray(new String[0]), chunk.toArray(new String[0]));
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
        String seriesTicker
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
                        orderbookClient = openOrderbookConnection(wrapper, cluster, connectionIndex);
                    }
                    OrderbookSubscriptionState state = subscribeDiscoveredChunk(
                        config,
                        orderbookClient,
                        channels,
                        chunk,
                        subscribed,
                        subscriptionSid
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
                            orderbookClient = openOrderbookConnection(wrapper, cluster, connectionIndex);
                        }
                        OrderbookSubscriptionState state = subscribeDiscoveredChunk(
                            config,
                            orderbookClient,
                            channels,
                            chunk,
                            subscribed,
                            subscriptionSid
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
                        orderbookClient = openOrderbookConnection(wrapper, cluster, connectionIndex);
                    }
                    OrderbookSubscriptionState state = subscribeDiscoveredChunk(
                        config,
                        orderbookClient,
                        channels,
                        chunk,
                        subscribed,
                        subscriptionSid
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
        int connectionIndex
    ) {
        System.out.println("Opening Kalshi orderbook websocket shard " + connectionIndex + ".");
        return new KalshiWebSocketClient(wrapper, cluster);
    }

    private static OrderbookSubscriptionState subscribeDiscoveredChunk(
        BackendConfig config,
        KalshiWebSocketClient wsClient,
        List<String> channels,
        List<String> chunk,
        int alreadySubscribed,
        long currentSid
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
        delayBetweenSubscriptions(config);
        return new OrderbookSubscriptionState(sid, totalAfterChunk);
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
