package edu.illinois.group8;

import edu.illinois.group8.wrapper.KalshiSession;
import edu.illinois.group8.wrapper.KalshiWebSocketClient;

public class KalshiSystem {

    private static String KEY_ID = "";
    private static String KEY_PATH = "/app/key.txt";

    private static KalshiSession instance = new KalshiSession("https://api.elections.kalshi.com", KEY_ID, KEY_PATH);

    public static void main(String[] args) {
//        KalshiWrapper wrapper = instance.getWrapper();
//        System.out.println(wrapper.getMarkets());
        KalshiWebSocketClient wsClient = instance.getWsClient();
        wsClient.subscribe(new String[]{"orderbook_delta"}, new String[]{"KXBTC-24DEC1417-B100750", "KXBTC-24DEC1417-B101250", "KXBTC-24DEC1417-B101750", "KXBTC-24DEC1417-B102250"});
    }

    public static KalshiSession getInstance() {
        return instance;
    }
}
