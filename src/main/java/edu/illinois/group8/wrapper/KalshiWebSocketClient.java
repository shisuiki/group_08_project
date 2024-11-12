package edu.illinois.group8.wrapper;

import edu.illinois.group8.KalshiSystem;
import edu.illinois.group8.event.EventManager;
import edu.illinois.group8.event.market.OrderBookDeltaEvent;
import edu.illinois.group8.event.market.OrderBookSnapshotEvent;
import edu.illinois.group8.event.market.TickerEvent;
import edu.illinois.group8.event.market.TradeEvent;
import edu.illinois.group8.utils.WebSocketClient;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class KalshiWebSocketClient extends WebSocketClient {

    private final KalshiWrapper wrapper;
    private Set<Long> subscriptionIds = new HashSet<>();
    private EventManager eventManager = KalshiSystem.getEventManager();
    private static final String PATH = "/trade-api/ws/v2";

    private long nonce = 1;

    public KalshiWebSocketClient(KalshiWrapper wrapper) {
        super(wrapper.getBaseUrl().replace("https://", "wss://") + PATH);
        this.wrapper = wrapper;

        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String msg = timestamp + "GET" + PATH;
            Map<String, String> headers = new HashMap<>();
            headers.put("KALSHI-ACCESS-KEY", wrapper.getKeyId());
            headers.put("KALSHI-ACCESS-SIGNATURE", wrapper.signMessage(msg));
            headers.put("KALSHI-ACCESS-TIMESTAMP", timestamp);
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
            switch (type) {
                case "subscribed":
                    // todo: save the subscription id
                    break;
                case "orderbook_snapshot":
                    OrderBookSnapshotEvent snapshotEvent = new OrderBookSnapshotEvent(data);
                    eventManager.callEvent(snapshotEvent);
                    break;
                case "orderbook_delta":
                    OrderBookDeltaEvent deltaEvent = new OrderBookDeltaEvent(data);
                    eventManager.callEvent(deltaEvent);
                    break;
                case "ticker":
                    TickerEvent tickerEvent = new TickerEvent(data);
                    eventManager.callEvent(tickerEvent);
                    break;
                case "trade":
                    TradeEvent tradeEvent = new TradeEvent(data);
                    eventManager.callEvent(tradeEvent);
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("Received message: " + message);
    }

    @Override
    public void onError(Exception e) {
        System.err.println("An error occurred: " + e.getMessage());
        e.printStackTrace();
    }

    @Override
    public void onClose() {
        System.out.println("Connection closed.");
    }

    public void subscribe(String[] channels, String marketTicker) {
        // todo: send subscribe message
    }

    public void subscribe(String[] channels, String[] marketTickers) {
        // todo: send subscribe message
    }

    public void unsubscribe(long[] subscriptionIds) {
        // todo: send unsubscribe message
    }

    public void update(long subscriptionId, String action, String marketTicker) {
        // todo: send update message
    }

    public void update(long subscriptionId, String action, String[] marketTickers) {
        // todo: send update message
    }

    private long getNonce() {
        long currentNonce = this.nonce;
        this.nonce++;
        return currentNonce;
    }

    public Set<Long> getSubscriptionIds() {
        return subscriptionIds;
    }
}

