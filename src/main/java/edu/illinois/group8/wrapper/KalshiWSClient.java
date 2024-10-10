package edu.illinois.group8.wrapper;

import edu.illinois.group8.KalshiSystem;
import edu.illinois.group8.event.*;
import edu.illinois.group8.event.market.OrderBookDeltaEvent;
import edu.illinois.group8.event.market.OrderBookSnapshotEvent;
import edu.illinois.group8.event.market.TickerEvent;
import edu.illinois.group8.event.market.TradeEvent;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import javax.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

@ClientEndpoint
public class KalshiWSClient {
    private final String uri;
    private final KalshiWrapper wrapper;
    private Session session;
    private Set<Long> subscriptionIds = new HashSet<>();
    private EventManager eventManager = KalshiSystem.getEventManager();

    public KalshiWSClient(KalshiWrapper wrapper) {
        this.wrapper = wrapper;
        String endpoint = "/trade-api/ws/v2";
        this.uri = wrapper.getBaseUrl().replace("https://", "wss://") + endpoint;
        connect();
    }

    private void connect() {
        try {
            // TODO: Needs authentication
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(this, URI.create(uri));
            System.out.println("Connected to " + uri);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        System.out.println("WebSocket opened.");
    }

    @OnMessage
    public void onMessage(String message) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject data = (JSONObject) parser.parse(message);
            String type = (String) data.get("type");
            switch (type) {
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

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        System.out.println("WebSocket closed: " + closeReason);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        throwable.printStackTrace();
    }

    public void close() throws IOException {
        if (session != null) {
            session.close();
            session = null;
        }
        System.out.println("WebSocket connection closed.");
    }

    public void subscribe(String channel) throws IOException {
        String message = String.format("{\"cmd\": \"subscribe\", \"params\": {\"channels\": [\"%s\"]}}", channel);
        session.getBasicRemote().sendText(message);
        System.out.println("Sent subscribe message: " + message);
    }
}

