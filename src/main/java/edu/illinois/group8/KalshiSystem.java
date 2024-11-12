package edu.illinois.group8;

import edu.illinois.group8.event.EventManager;
import edu.illinois.group8.wrapper.KalshiSession;
import edu.illinois.group8.wrapper.KalshiWebSocketClient;
import edu.illinois.group8.wrapper.KalshiWrapper;
import org.json.JSONObject;

public class KalshiSystem {

    private static String KEY_ID = "";
    private static String KEY_PATH = "";

    private static EventManager eventManager = new EventManager();
    private static KalshiSession instance = new KalshiSession("https://trading-api.kalshi.com", KEY_ID, KEY_PATH);

    public static void main(String[] args) {
        KalshiWrapper wrapper = instance.getWrapper();
        System.out.println(wrapper.getMarkets());
        KalshiWebSocketClient wsClient = instance.getWsClient();
        wsClient.sendMessage("{\"id\": 1,\"cmd\": \"subscribe\",\"params\": {\"channels\": [\"orderbook_delta\"], \"market_tickers\": [\"RATECUTCOUNT-24DEC31-T4\"]}}");
    }

    public static EventManager getEventManager() {
        return eventManager;
    }

    public static KalshiSession getInstance() {
        return instance;
    }
}
