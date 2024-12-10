package edu.illinois.group8;

import edu.illinois.group8.wrapper.KalshiSession;
import edu.illinois.group8.wrapper.KalshiWebSocketClient;
import edu.illinois.group8.wrapper.KalshiWrapper;

import java.util.logging.Logger;

public class KalshiSystem {

    private static String KEY_ID = "";
    private static String KEY_PATH = "";

    private static KalshiSession instance = new KalshiSession("https://api.elections.kalshi.com", KEY_ID, KEY_PATH);

    public static void main(String[] args) {
//        KalshiWrapper wrapper = instance.getWrapper();
//        System.out.println(wrapper.getMarkets());
        KalshiWebSocketClient wsClient = instance.getWsClient();
        wsClient.subscribe(new String[]{"orderbook_delta"}, new String[]{"RATECUTCOUNT-24DEC31-T3", "RATECUTCOUNT-24DEC31-T4", "RATECUTCOUNT-24DEC31-T5"});
    }

    public static KalshiSession getInstance() {
        return instance;
    }
}
