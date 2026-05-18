package edu.illinois.group8.wrapper;

import edu.illinois.group8.cluster.ClientClusterOrchestrator;
import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.recorder.RawIngestRecorder;
import edu.illinois.group8.storage.db.RawDbIngestSink;
import edu.illinois.group8.time.TimestampSource;
import edu.illinois.group8.utils.WebSocketClient;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class KalshiWebSocketClient extends WebSocketClient {

    private final KalshiWrapper wrapper;
    private static final String PATH = "/trade-api/ws/v2";
    private final ClientClusterOrchestrator cluster;
    private final boolean closeClusterOnClose;
    private final RawIngestRecorder rawIngestRecorder;
    private final String rawIngestConnectionId;
    private final TimestampSource timestampSource;
    private final KalshiInboundMessageHandler inboundMessageHandler;
    private final Map<Long, CompletableFuture<Long>> subscriptionAcks = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Void>> updateAcks = new ConcurrentHashMap<>();
    
    private long nonce = 1;

    private static ClientClusterOrchestrator initClusterConn() {
        BackendConfig config = BackendConfig.fromEnvironment();
        return new ClientClusterOrchestrator(config.clusterAddresses(), config.hostIp());
    }

    public KalshiWebSocketClient(KalshiWrapper wrapper) {
        this(wrapper, initClusterConn(), true, null);
    }

    public KalshiWebSocketClient(
        KalshiWrapper wrapper,
        RawDbIngestSink.RawDbIngestConnection rawDbConnection
    ) {
        this(wrapper, initClusterConn(), true, rawDbConnection);
    }

    public KalshiWebSocketClient(KalshiWrapper wrapper, ClientClusterOrchestrator cluster) {
        this(wrapper, cluster, false, null);
    }

    public KalshiWebSocketClient(
        KalshiWrapper wrapper,
        ClientClusterOrchestrator cluster,
        RawDbIngestSink.RawDbIngestConnection rawDbConnection
    ) {
        this(wrapper, cluster, false, rawDbConnection);
    }

    private KalshiWebSocketClient(
        KalshiWrapper wrapper,
        ClientClusterOrchestrator cluster,
        boolean closeClusterOnClose,
        RawDbIngestSink.RawDbIngestConnection rawDbConnection
    ) {
        super(wrapper.getBaseUrl().replace("https://", "wss://") + PATH);
        this.wrapper = wrapper;
        this.cluster = cluster;
        this.closeClusterOnClose = closeClusterOnClose;
        this.rawIngestRecorder = RawIngestRecorder.fromEnvironment();
        this.rawIngestConnectionId = rawIngestRecorder.newConnectionId();
        this.timestampSource = TimestampSource.fromEnvironment();
        this.inboundMessageHandler = new KalshiInboundMessageHandler(
            cluster::writeToCluster,
            rawIngestRecorder::recordInbound,
            rawDbRecorderFor(rawDbConnection),
            new KalshiAckCallbacks(),
            rawIngestConnectionId
        );

        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String msg = timestamp + "GET" + PATH;
            Map<String, String> headers = new HashMap<>();
            headers.put("KALSHI-ACCESS-KEY", wrapper.getKeyId());
            headers.put("KALSHI-ACCESS-SIGNATURE", wrapper.signMessage(msg));
            headers.put( "KALSHI-ACCESS-TIMESTAMP", timestamp);
            headers.put("accept", "application/json");

            this.connect(PATH, headers);
        } catch (Exception e) {
            System.err.println("Signing websocket handshake request threw exception: " + e.getMessage());
        }
    }

    static KalshiInboundMessageHandler.RawDbRecorder rawDbRecorderFor(
        RawDbIngestSink.RawDbIngestConnection rawDbConnection
    ) {
        if (rawDbConnection == null) {
            return KalshiInboundMessageHandler.RawDbRecorder.disabled();
        }
        return rawDbConnection::recordInbound;
    }

    @Override
    public void onOpen() {
        System.out.println("Connection opened.");
    }

    @Override
    public void onMessage(String message) {
        long receiveTsNs = timestampSource.nowNanos();
        Instant receiveWallTs = Instant.now();
        inboundMessageHandler.handleInbound(message, receiveTsNs, receiveWallTs);
    }

    @Override
    public void onError(Exception e) {
        System.err.println("An error occurred: " + e.getMessage());
        e.printStackTrace();
    }

    @Override
    public void onClose() {
        completePendingAcksExceptionally("Kalshi websocket closed: " + closeDescription());
        if (closeClusterOnClose) {
            this.cluster.close();
        }
        System.out.println("Connection closed (" + closeDescription() + ").");
    }

    public boolean subscribe(String[] channels, String marketTicker) {
        JSONObject params = new JSONObject();
        JSONArray channelArray = new JSONArray();
        channelArray.addAll(Arrays.asList(channels));
        params.put("channels", channelArray);
        params.put("market_ticker", marketTicker);
        JSONObject object = new JSONObject();
        object.put("id", getNonce());
        object.put("cmd", "subscribe");
        object.put("params", params);
        return sendMessage(object.toJSONString());
    }

    public boolean subscribe(String[] channels) {
        JSONObject params = new JSONObject();
        JSONArray channelArray = new JSONArray();
        channelArray.addAll(Arrays.asList(channels));
        params.put("channels", channelArray);
        JSONObject object = new JSONObject();
        object.put("id", getNonce());
        object.put("cmd", "subscribe");
        object.put("params", params);
        return sendMessage(object.toJSONString());
    }

    public boolean subscribe(String[] channels, String[] marketTickers) {
        JSONObject object = buildSubscribeCommand(channels, marketTickers, getNonce());
        return sendMessage(object.toJSONString());
    }

    public long subscribeAndAwaitSid(String[] channels, String[] marketTickers, int timeoutMs)
        throws InterruptedException {
        long commandId = getNonce();
        CompletableFuture<Long> future = new CompletableFuture<>();
        subscriptionAcks.put(commandId, future);
        JSONObject object = buildSubscribeCommand(channels, marketTickers, commandId);
        if (!sendMessage(object.toJSONString())) {
            subscriptionAcks.remove(commandId);
            throw new IllegalStateException("WebSocket was closed before subscribe could be sent: " + closeDescription());
        }
        return awaitAck(commandId, future, "subscribe", timeoutMs);
    }

    private JSONObject buildSubscribeCommand(String[] channels, String[] marketTickers, long commandId) {
        JSONObject params = new JSONObject();
        JSONArray channelArray = new JSONArray();
        channelArray.addAll(Arrays.asList(channels));
        params.put("channels", channelArray);
        JSONArray marketTickerArray = new JSONArray();
        marketTickerArray.addAll(Arrays.asList(marketTickers));
        params.put("market_tickers", marketTickerArray);
        JSONObject object = new JSONObject();
        object.put("id", commandId);
        object.put("cmd", "subscribe");
        object.put("params", params);
        return object;
    }

    public boolean unsubscribe(long[] subscriptionIds) {
        JSONObject params = new JSONObject();
        JSONArray sidsArray = new JSONArray();
        Arrays.stream(subscriptionIds).forEach(sidsArray::add);
        params.put("sids", sidsArray);
        JSONObject object = new JSONObject();
        object.put("id", getNonce());
        object.put("cmd", "unsubscribe");
        object.put("params", params);
        return sendMessage(object.toJSONString());
    }

    public boolean update(long subscriptionId, String action, String marketTicker) {
        if (!action.equals("add_markets") && !action.equals("delete_markets")) return false;
        JSONObject params = new JSONObject();
        JSONArray sidsArray = new JSONArray();
        sidsArray.add(subscriptionId);
        params.put("sids", sidsArray);
        params.put("action", action);
        params.put("market_ticker", marketTicker);
        JSONObject object = new JSONObject();
        object.put("id", getNonce());
        object.put("cmd", "update_subscription");
        object.put("params", params);
        return sendMessage(object.toJSONString());
    }

    public boolean update(long subscriptionId, String action, String[] marketTickers) {
        if (!action.equals("add_markets") && !action.equals("delete_markets")) return false;
        return sendUpdateCommand(subscriptionId, action, marketTickers, getNonce());
    }

    public void updateAndAwaitOk(long subscriptionId, String action, String[] marketTickers, int timeoutMs)
        throws InterruptedException {
        if (!action.equals("add_markets") && !action.equals("delete_markets")) {
            throw new IllegalArgumentException("Unsupported update_subscription action: " + action);
        }
        long commandId = getNonce();
        CompletableFuture<Void> future = new CompletableFuture<>();
        updateAcks.put(commandId, future);
        if (!sendUpdateCommand(subscriptionId, action, marketTickers, commandId)) {
            updateAcks.remove(commandId);
            throw new IllegalStateException("WebSocket was closed before update_subscription could be sent: " + closeDescription());
        }
        awaitAck(commandId, future, "update_subscription", timeoutMs);
    }

    private boolean sendUpdateCommand(long subscriptionId, String action, String[] marketTickers, long commandId) {
        JSONObject params = new JSONObject();
        JSONArray sidsArray = new JSONArray();
        sidsArray.add(subscriptionId);
        params.put("sids", sidsArray);
        params.put("action", action);
        JSONArray marketTickerArray = new JSONArray();
        marketTickerArray.addAll(Arrays.asList(marketTickers));
        params.put("market_tickers", marketTickerArray);
        JSONObject object = new JSONObject();
        object.put("id", commandId);
        object.put("cmd", "update_subscription");
        object.put("params", params);
        return sendMessage(object.toJSONString());
    }

    private synchronized long getNonce() {
        long currentNonce = this.nonce;
        this.nonce++;
        return currentNonce;
    }

    private <T> T awaitAck(long commandId, CompletableFuture<T> future, String command, int timeoutMs)
        throws InterruptedException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exc) {
            subscriptionAcks.remove(commandId);
            updateAcks.remove(commandId);
            throw new IllegalStateException("Timed out waiting for Kalshi " + command + " acknowledgement id=" + commandId, exc);
        } catch (ExecutionException exc) {
            throw new IllegalStateException("Kalshi " + command + " acknowledgement failed id=" + commandId, exc.getCause());
        }
    }

    private void completeAckExceptionally(Long id, String message) {
        if (id == null) {
            return;
        }
        RuntimeException exception = new IllegalStateException(message);
        CompletableFuture<Long> subscriptionFuture = subscriptionAcks.remove(id);
        if (subscriptionFuture != null) {
            subscriptionFuture.completeExceptionally(exception);
        }
        CompletableFuture<Void> updateFuture = updateAcks.remove(id);
        if (updateFuture != null) {
            updateFuture.completeExceptionally(exception);
        }
    }

    private void completePendingAcksExceptionally(String message) {
        RuntimeException exception = new IllegalStateException(message);
        subscriptionAcks.forEach((id, future) -> future.completeExceptionally(exception));
        updateAcks.forEach((id, future) -> future.completeExceptionally(exception));
        subscriptionAcks.clear();
        updateAcks.clear();
    }

    private final class KalshiAckCallbacks implements KalshiInboundMessageHandler.AckCallbacks {
        @Override
        public boolean shouldParseInboundAcks() {
            return !subscriptionAcks.isEmpty() || !updateAcks.isEmpty();
        }

        @Override
        public void onError(Long id, Long code, String message) {
            System.out.println("Received Kalshi error code " + code + ": " + message);
            completeAckExceptionally(id, "Kalshi error code " + code + ": " + message);
        }

        @Override
        public void onSubscribed(Long id, Long sid) {
            CompletableFuture<Long> future = id == null ? null : subscriptionAcks.remove(id);
            if (future != null && sid != null) {
                future.complete(sid);
            }
        }

        @Override
        public void onOk(Long id) {
            CompletableFuture<Void> future = id == null ? null : updateAcks.remove(id);
            if (future != null) {
                future.complete(null);
            }
        }
    }

}
