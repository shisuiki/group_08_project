package edu.illinois.group8;

import edu.illinois.group8.wrapper.KalshiSession;
import edu.illinois.group8.wrapper.KalshiWebSocketClient;
import edu.illinois.group8.wrapper.KalshiWrapper;
import edu.illinois.group8.wrapper.RequestParameters;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.ArrayList;
import java.util.List;

public class KalshiSystem {

    private static String KEY_ID = "";  // Put your API key ID here
    private static String KEY_PATH = "/app/key.txt";

    private static KalshiSession session = new KalshiSession("https://api.elections.kalshi.com", KEY_ID, KEY_PATH);

    public static void main(String[] args) {
        KalshiWrapper wrapper = session.getWrapper();
        List<String> tickers = new ArrayList<>();
        RequestParameters params = new RequestParameters();
        params.addParam("status", "open");
        params.addParam("series_ticker", "KXHIGHCHI"); // Chicago weather contracts
        String marketsStr = wrapper.getMarkets(params);
        JSONParser parser = new JSONParser();
        try {
            JSONObject data = (JSONObject) parser.parse(marketsStr);
            JSONArray markets = (JSONArray) data.get("markets");
            for (Object marketObj : markets) {
                String ticker = (String) ((JSONObject) marketObj).get("ticker");
                tickers.add(ticker);
            }
        } catch (ParseException exc) {
            exc.printStackTrace();
        }
        KalshiWebSocketClient wsClient = session.getWsClient();
        wsClient.subscribe(new String[]{"orderbook_delta", "trade", "ticker"}, tickers.toArray(new String[0]));
    }
}
