package edu.illinois.group8.wrapper;

import edu.illinois.group8.cluster.ClientClusterOrchestrator;
import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.utils.WebSocketClient;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class KalshiWebSocketClient extends WebSocketClient {

    private final KalshiWrapper wrapper;
    private static final String PATH = "/trade-api/ws/v2";
    private final ClientClusterOrchestrator cluster;
    
    private long nonce = 1;

    public ClientClusterOrchestrator initClusterConn() {
        BackendConfig config = BackendConfig.fromEnvironment();
        return new ClientClusterOrchestrator(config.clusterAddresses(), config.hostIp());
    }
    public KalshiWebSocketClient(KalshiWrapper wrapper) {
        super(wrapper.getBaseUrl().replace("https://", "wss://") + PATH);
        this.wrapper = wrapper;
        
        cluster = initClusterConn();

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

    @Override
    public void onOpen() {
        System.out.println("Connection opened.");
    }

    @Override
    public void onMessage(String message) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject data = (JSONObject) parser.parse(message);
            String type = (String) data.get("type");
            JSONObject msg = (JSONObject) data.get("msg");
            if ("error".equals(type) && msg != null) {
                int code = ((Long) msg.get("code")).intValue();
                String errorMsg = (String) msg.get("msg");
                System.out.println("Received Kalshi error code " + code + ": " + errorMsg);
            }
            cluster.writeToCluster(message);
        } catch (Exception e) {
            cluster.writeToCluster(message);
        }
        
    }

    @Override
    public void onError(Exception e) {
        System.err.println("An error occurred: " + e.getMessage());
        e.printStackTrace();
    }

    @Override
    public void onClose() {
        this.cluster.close();
        System.out.println("Connection closed.");
    }

    public void subscribe(String[] channels, String marketTicker) {
        JSONObject params = new JSONObject();
        JSONArray channelArray = new JSONArray();
        channelArray.addAll(Arrays.asList(channels));
        params.put("channels", channelArray);
        params.put("market_ticker", marketTicker);
        JSONObject object = new JSONObject();
        object.put("id", getNonce());
        object.put("cmd", "subscribe");
        object.put("params", params);
        sendMessage(object.toJSONString());
    }

    public void subscribe(String[] channels, String[] marketTickers) {
        JSONObject params = new JSONObject();
        JSONArray channelArray = new JSONArray();
        channelArray.addAll(Arrays.asList(channels));
        params.put("channels", channelArray);
        JSONArray marketTickerArray = new JSONArray();
        marketTickerArray.addAll(Arrays.asList(marketTickers));
        params.put("market_tickers", marketTickerArray);
        JSONObject object = new JSONObject();
        object.put("id", getNonce());
        object.put("cmd", "subscribe");
        object.put("params", params);
        sendMessage(object.toJSONString());
    }

    public void unsubscribe(long[] subscriptionIds) {
        JSONObject params = new JSONObject();
        JSONArray sidsArray = new JSONArray();
        Arrays.stream(subscriptionIds).forEach(sidsArray::add);
        params.put("sids", subscriptionIds);
        JSONObject object = new JSONObject();
        object.put("id", getNonce());
        object.put("cmd", "unsubscribe");
        object.put("params", params);
        sendMessage(object.toJSONString());
    }

    public void update(long subscriptionId, String action, String marketTicker) {
        if (!action.equals("add_markets") && !action.equals("delete_markets")) return;
        JSONObject params = new JSONObject();
        params.put("sids", new Long[]{subscriptionId});
        params.put("action", action);
        params.put("market_ticker", marketTicker);
        JSONObject object = new JSONObject();
        object.put("id", getNonce());
        object.put("cmd", "update_subscription");
        object.put("params", params);
        sendMessage(object.toJSONString());
    }

    public void update(long subscriptionId, String action, String[] marketTickers) {
        if (!action.equals("add_markets") && !action.equals("delete_markets")) return;
        JSONObject params = new JSONObject();
        params.put("sids", new Long[]{subscriptionId});
        params.put("action", action);
        JSONArray marketTickerArray = new JSONArray();
        marketTickerArray.addAll(Arrays.asList(marketTickers));
        params.put("market_tickers", marketTickerArray);
        JSONObject object = new JSONObject();
        object.put("id", getNonce());
        object.put("cmd", "update_subscription");
        object.put("params", params);
        sendMessage(object.toJSONString());
    }

    private long getNonce() {
        long currentNonce = this.nonce;
        this.nonce++;
        return currentNonce;
    }

}
