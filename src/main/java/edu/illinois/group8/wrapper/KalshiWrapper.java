package edu.illinois.group8.wrapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashMap;
import java.util.Map;

import edu.illinois.group8.wrapper.RequestParameters;

public class KalshiWrapper {

    private String baseUrl;
    private final HttpClient httpClient;

    private final String SCHED_URL = "/trade-api/v2/exchange/schedule";
    private final String ANNOUNCE_URL = "/trade-api/v2/exchange/status";
    private final String EVENTS_URL = "/trade-api/v2/events";
    private final String MARKETS_URL = "/trade-api/v2/markets";
    private final String SERIES_URL = "/trade-api/v2/series";

    public KalshiWrapper() {
        this(false);
    }

    public KalshiWrapper(boolean useDemo) {
        this.baseUrl = useDemo ? "https://demo-api.kalshi.com" : "https://trading-api.kalshi.com";
        this.httpClient = HttpClient.newHttpClient();
    }

    private String sendGet(String path, String paramsString) {
        long currentTimeMilli = System.currentTimeMillis();
        String timestamp = String.valueOf(currentTimeMilli);

        if (paramsString != null && !paramsString.isEmpty()) {
            if (paramsString.charAt(0) != '?') {
                path += "?";
            }
            path += paramsString;
        }
        String url = baseUrl + path;

        StringBuilder urlBuilder = new StringBuilder(url);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(urlBuilder.toString()))
            .GET()
            .setHeader("content-type", "application/json")
            .setHeader("accept", "application/json")
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("HTTP request to " + urlBuilder.toString() + " failed with status code " + response.statusCode() + " (Error message: " + response.body() + ")");
                return null;
            }
        } catch (Exception e) {
            System.err.println("HTTP request to " + urlBuilder.toString() + " threw exception with message " + e.getMessage());
            return null;
        }

        return response.body();
    }

    private String sendGet(String path) {
        return sendGet(path, "");
    }

    public String getExchangeSchedule() {
        return sendGet(SCHED_URL);
    }

    public String getExchangeAnnouncements() {
        return sendGet(ANNOUNCE_URL);
    }

    public String getEvents(RequestParameters params) {
        String paramsString = "";
        for (Map.Entry<String, Object> entry : params.getParams().entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Integer && key.equals("limit")) {
                paramsString += key + "=" + String.valueOf(val) + "&";
            } else if (val instanceof String && (key.equals("cursor") || key.equals("series_ticker"))) {
                paramsString += key + "=" + val + "&";
            } else if (val instanceof String && key.equals("status")) {
                paramsString += key + "=" + ((String) val).replace(" ", "");
            } else if (val instanceof Boolean && key.equals("with_nested_markets")) {
                paramsString += key + "=" + String.valueOf(val) + "&";
            }
        }
        return sendGet(EVENTS_URL, paramsString);
    }

    public String getEvents() {
        return sendGet(EVENTS_URL);
    }

    public String getEvent(String eventTicker, RequestParameters params) {
        String paramsString = "";
        for (Map.Entry<String, Object> entry : params.getParams().entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Boolean && key.equals("with_nested_markets")) {
                paramsString += key + "=" + String.valueOf(val) + "&";
            }
        }
        return sendGet(EVENTS_URL + "/" + eventTicker, paramsString);
    }

    public String getEvent(String eventTicker) {
        return sendGet(EVENTS_URL + "/" + eventTicker);
    }

    // WIP: need authorization for getMarkets API call
    public String getMarkets(RequestParameters params) {
        String paramsString = "";
        for (Map.Entry<String, Object> entry : params.getParams().entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Integer && (key.equals("limit") || key.equals("max_close_ts") || key.equals("min_close_ts"))) {
                paramsString += key + "=" + String.valueOf(val) + "&";
            } else if (val instanceof String && (key.equals("cursor") || key.equals("event_ticker") || key.equals("series_ticker"))) {
                paramsString += key + "=" + val + "&";
            } else if (val instanceof String && (key.equals("status") || key.equals("tickers"))) {
                paramsString += key + "=" + ((String) val).replace(" ", "");
            }
        }
        return sendGet(MARKETS_URL, paramsString);
    }

    public String getMarkets() {
        return sendGet(MARKETS_URL);
    }

    public String getTrades(RequestParameters params) {
        String paramsString = "";
        for (Map.Entry<String, Object> entry : params.getParams().entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Integer && (key.equals("limit") || key.equals("max_ts") || key.equals("min_ts"))) {
                paramsString += key + "=" + String.valueOf(val) + "&";
            } else if (val instanceof String && (key.equals("cursor") || key.equals("ticker"))) {
                paramsString += key + "=" + val + "&";
            }
        }
        return sendGet(MARKETS_URL + "/trades", paramsString);
    }

    public String getTrades() {
        return sendGet(MARKETS_URL + "/trades");
    }

    public String getMarket(String ticker) {
        return sendGet(MARKETS_URL + "/" + ticker);
    }

    // WIP: need authorization for getMarketOrderbook API call
    public String getMarketOrderbook(String ticker, RequestParameters params) {
        String paramsString = "";
        for (Map.Entry<String, Object> entry : params.getParams().entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Integer && key.equals("depth")) {
                paramsString += key + "=" + String.valueOf(val) + "&";
            }
        }
        return sendGet(MARKETS_URL + "/" + ticker + "/orderbook");
    }

    public String getMarketOrderbook(String ticker) {
        return sendGet(MARKETS_URL + "/" + ticker + "/orderbook");
    }

    public String getSeries(String series_ticker) {
        return sendGet(SERIES_URL + "/" + series_ticker);
    }

    // WIP: need authorization for getMarketCandlesticks API call
    public String getMarketCandlesticks(String ticker, String series_ticker, Integer start_ts, Integer end_ts, Integer period_interval) {
        String paramsString = "start_ts=" + start_ts + "&end_ts=" + end_ts + "&period_interval=" + period_interval;
        return sendGet(SERIES_URL + "/" + series_ticker + "/markets/" + ticker + "/candlesticks", paramsString);
    }
    public String getBaseUrl() {
        return baseUrl;
    }
}
