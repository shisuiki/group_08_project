package edu.illinois.group8.wrapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashMap;
import java.util.Map;

import java.security.PrivateKey;

import edu.illinois.group8.wrapper.RequestParameters;
import edu.illinois.group8.utils.PrivateKeyLoader;
import edu.illinois.group8.utils.RSAMessageSigner;

public class KalshiWrapper {

    private String baseUrl;
    private final HttpClient httpClient;

    private final String SCHED_URL = "/trade-api/v2/exchange/schedule";
    private final String ANNOUNCE_URL = "/trade-api/v2/exchange/status";
    private final String EVENTS_URL = "/trade-api/v2/events";
    private final String MARKETS_URL = "/trade-api/v2/markets";
    private final String SERIES_URL = "/trade-api/v2/series";
    private final String LOGIN_URL = "/trade-api/v2/login";

    private PrivateKey privateKey = null;

    public KalshiWrapper() {
        this(false);
    }

    public KalshiWrapper(boolean useDemo) {
        this.baseUrl = useDemo ? "https://demo-api.kalshi.com" : "https://trading-api.kalshi.com";
        this.httpClient = HttpClient.newHttpClient();
    }

    public void loadPrivateKey(String filepath) {
        try {
            this.privateKey = PrivateKeyLoader.loadPrivateKeyFromFile(filepath);
        } catch (Exception e) {
            System.err.println("Loading private key from filepath " + filepath + " threw exception with message: " + e.getMessage());
        }
    }

    private String sendGetRequest(HttpRequest request) {
        HttpResponse<String> response;
        
        try {
            response = httpClient.send(request, BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("HTTP request to failed with status code " + response.statusCode() + " (Error message: " + response.body() + ")");
                return null;
            }
        } catch (Exception e) {
            System.err.println("HTTP request to threw exception with message " + e.getMessage());
            return null;
        }

        return response.body();
    }

    private String sendGet(String path, String paramsString) {
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

        return sendGetRequest(request);
    }

    private String sendGet(String path) {
        return sendGet(path, "");
    }

    private String sendAuthorizedGet(String path, String token, String paramsString) {
        long currentTimeMilli = System.currentTimeMillis();
        String timestamp = String.valueOf(currentTimeMilli);
        
        if (paramsString != null && !paramsString.isEmpty()) {
            if (paramsString.charAt(0) != '?') {
                path += "?";
            }
            path += paramsString;
        }

        String message = timestamp + "GET" + path;
        
        String sig;
        try {
            sig = RSAMessageSigner.signPssText(privateKey, message);
        } catch (Exception e) {
            System.err.println("caught exception during signing: " + e.getMessage());
            return null;
        }

        String url = baseUrl + path;

        StringBuilder urlBuilder = new StringBuilder(url);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(urlBuilder.toString()))
            .GET()
            .setHeader("content-type", "application/json")
            .setHeader("accept", "application/json")
            .setHeader("KALSHI-ACCESS-KEY", "7d80a2f5-487d-4bdf-bb36-4339673593c4")
            .setHeader("KALSHI-ACCESS-SIGNATURE", sig)
            .setHeader("KALSHI-ACCESS-TIMESTAMP", timestamp)
            .setHeader("Authorization", "Bearer " + token)
            .build();

        return sendGetRequest(request);
    }

    private String sendAuthorizedGet(String path, String token) {
        return sendAuthorizedGet(path, token, "");
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
    public String getMarkets(String token, RequestParameters params) {
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
        return sendAuthorizedGet(MARKETS_URL, token, paramsString);
    }

    public String getMarkets(String token) {
        return sendAuthorizedGet(MARKETS_URL, token);
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
    public String getMarketOrderbook(String token, String ticker, RequestParameters params) {
        String paramsString = "";
        for (Map.Entry<String, Object> entry : params.getParams().entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Integer && key.equals("depth")) {
                paramsString += key + "=" + String.valueOf(val) + "&";
            }
        }
        return sendAuthorizedGet(MARKETS_URL + "/" + ticker + "/orderbook", token, paramsString);
    }

    public String getMarketOrderbook(String token, String ticker) {
        return sendAuthorizedGet(MARKETS_URL + "/" + ticker + "/orderbook", token);
    }

    public String getSeries(String series_ticker) {
        return sendGet(SERIES_URL + "/" + series_ticker);
    }

    // WIP: need authorization for getMarketCandlesticks API call
    public String getMarketCandlesticks(String token, String ticker, String series_ticker, Integer start_ts, Integer end_ts, Integer period_interval) {
        String paramsString = "start_ts=" + start_ts + "&end_ts=" + end_ts + "&period_interval=" + period_interval;
        return sendAuthorizedGet(SERIES_URL + "/" + series_ticker + "/markets/" + ticker + "/candlesticks", token, paramsString);
    }

    public String login(String email, String password) {
        String url = baseUrl + LOGIN_URL;

        StringBuilder urlBuilder = new StringBuilder(url);

        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(urlBuilder.toString()))
            .POST(body)
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

    public String getBaseUrl() {
        return baseUrl;
    }
}
