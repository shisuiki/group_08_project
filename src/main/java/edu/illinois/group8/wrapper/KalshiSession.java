package edu.illinois.group8.wrapper;

import edu.illinois.group8.KalshiSystem;
import edu.illinois.group8.event.EventManager;
import edu.illinois.group8.listeners.OrderBookListener;

import java.util.HashMap;
import java.util.Map;

public class KalshiSession {
    private KalshiWrapper wrapper;
    private KalshiWSClient wsClient;
    private Map<String, OrderBook> orderBooks = new HashMap<>();

    public KalshiSession(String keyId, String path) {
        this.wrapper = new KalshiWrapper();
        // TODO: Uncomment when WS client is solved
//        this.wsClient = new KalshiWSClient(this.wrapper);
        registerListeners();
        this.wrapper.loadPrivateKey(keyId, path);
    }

    private void registerListeners() {
        EventManager eventManager = KalshiSystem.getEventManager();
        eventManager.register(new OrderBookListener());
    }

    public KalshiWrapper getWrapper() {
        return wrapper;
    }
}
