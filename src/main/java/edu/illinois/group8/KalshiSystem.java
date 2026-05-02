package edu.illinois.group8;

import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.wrapper.KalshiSession;
import edu.illinois.group8.wrapper.KalshiWebSocketClient;
import edu.illinois.group8.wrapper.KalshiWrapper;
import edu.illinois.group8.wrapper.RequestParameters;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.ArrayList;
import java.util.List;

public class KalshiSystem {
    public static void main(String[] args) {
        BackendConfig config = BackendConfig.fromEnvironment();
        config.validateForLiveIngestion();

        KalshiSession session = new KalshiSession(config.kalshiBaseUrl(), config.kalshiKeyId(), config.kalshiKeyPath());
        KalshiWrapper wrapper = session.getWrapper();
        List<String> tickers = resolveMarketTickers(config, wrapper);
        if (tickers.isEmpty()) {
            throw new IllegalStateException("No market tickers resolved for live subscription.");
        }

        KalshiWebSocketClient wsClient = session.getWsClient();
        wsClient.subscribe(config.websocketChannels().toArray(new String[0]), tickers.toArray(new String[0]));
    }

    private static List<String> resolveMarketTickers(BackendConfig config, KalshiWrapper wrapper) {
        if (!config.marketTickers().isEmpty()) {
            return config.marketTickers();
        }

        RequestParameters params = new RequestParameters();
        params.addParam("status", "open");
        params.addParam("series_ticker", config.marketSeriesTicker());
        String marketsStr = wrapper.getMarkets(params);
        if (marketsStr == null) {
            throw new IllegalStateException("Kalshi markets request returned null for series " + config.marketSeriesTicker());
        }

        List<String> tickers = new ArrayList<>();
        JSONParser parser = new JSONParser();
        try {
            JSONObject data = (JSONObject) parser.parse(marketsStr);
            JSONArray markets = (JSONArray) data.get("markets");
            for (Object marketObj : markets) {
                String ticker = (String) ((JSONObject) marketObj).get("ticker");
                if (ticker != null && !ticker.isBlank()) {
                    tickers.add(ticker);
                }
            }
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to parse Kalshi markets response", exc);
        }
        return tickers;
    }
}
