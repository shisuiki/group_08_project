package edu.illinois.group8.wrapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import edu.illinois.group8.utils.Cryptography;

public class KalshiWrapper {

    private String baseUrl;
    private final HttpClient httpClient;

    private final String SCHED_URL = "/trade-api/v2/exchange/schedule";
    private final String ANNOUNCE_URL = "/trade-api/v2/exchange/status";
    private final String EVENTS_URL = "/trade-api/v2/events";
    private final String MARKETS_URL = "/trade-api/v2/markets";
    private final String SERIES_URL = "/trade-api/v2/series";

    private PrivateKey privateKey = null;
    private String keyId;

    public KalshiWrapper(String baseUrl, String keyId, String keyPath) {
        this(baseUrl, keyId, keyPath, "");
    }

    public KalshiWrapper(String baseUrl, String keyId, String keyPath, String keyPem) {
        this.baseUrl = baseUrl;
        this.keyId = keyId;
        this.httpClient = HttpClient.newHttpClient();
        if (keyPath != null && !keyPath.isBlank()) {
            try {
                this.privateKey = Cryptography.loadPrivateKey(keyPath);
            } catch (Exception e) {
                System.err.println("Loading private key from configured filepath failed");
            }
        } else if (keyPem != null && !keyPem.isBlank()) {
            try {
                this.privateKey = Cryptography.loadPrivateKeyFromPem(keyPem);
            } catch (Exception e) {
                System.err.println("Loading private key from configured PEM failed");
            }
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

    public String sendAuthorizedGet(String path, String paramsString) {
        if (!hasCredentials()) {
            return sendGet(path, paramsString);
        }
        long currentTimeMilli = System.currentTimeMillis();
        String timestamp = String.valueOf(currentTimeMilli);

        String url = baseUrl + path;

        if (paramsString != null && !paramsString.isEmpty()) {
            if (paramsString.charAt(0) != '?') {
                url += "?";
            }
            url += paramsString;
        }

        String message = timestamp + "GET" + path;

        String sig;
        try {
            sig = this.signMessage(message);
        } catch (Exception e) {
            System.err.println("caught exception during signing: " + e.getMessage());
            return null;
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .setHeader("content-type", "application/json")
            .setHeader("accept", "application/json")
            .setHeader("KALSHI-ACCESS-KEY", this.keyId)
            .setHeader("KALSHI-ACCESS-SIGNATURE", sig)
            .setHeader("KALSHI-ACCESS-TIMESTAMP", timestamp)
            .build();

        return sendGetRequest(request);
    }

    private String sendAuthorizedGet(String path) {
        return sendAuthorizedGet(path, "");
    }

    public String getExchangeSchedule() {
        return sendGet(SCHED_URL);
    }

    public String getExchangeAnnouncements() {
        return sendGet(ANNOUNCE_URL);
    }

    public String getEvents(RequestParameters params) {
        String paramsString = buildParams(params, "limit", "cursor", "series_ticker", "status", "with_nested_markets");
        return sendGet(EVENTS_URL, paramsString);
    }

    public String getEvents() {
        return sendGet(EVENTS_URL);
    }

    public String getEvent(String eventTicker, RequestParameters params) {
        String paramsString = buildParams(params, "with_nested_markets");
        return sendGet(EVENTS_URL + "/" + eventTicker, paramsString);
    }

    public String getEvent(String eventTicker) {
        return sendGet(EVENTS_URL + "/" + eventTicker);
    }

    public String getMarkets(RequestParameters params) {
        String paramsString = buildParams(
            params,
            "limit",
            "cursor",
            "event_ticker",
            "series_ticker",
            "status",
            "tickers",
            "min_created_ts",
            "max_created_ts",
            "min_close_ts",
            "max_close_ts",
            "min_settled_ts",
            "max_settled_ts",
            "min_updated_ts",
            "mve_filter"
        );
        return sendAuthorizedGet(MARKETS_URL, paramsString);
    }

    public String getMarkets() {
        return sendAuthorizedGet(MARKETS_URL);
    }

    public String getTrades(RequestParameters params) {
        String paramsString = buildParams(params, "limit", "cursor", "ticker", "min_ts", "max_ts");
        return sendGet(MARKETS_URL + "/trades", paramsString);
    }

    public String getTrades() {
        return sendGet(MARKETS_URL + "/trades");
    }

    public String getMarket(String ticker) {
        return sendGet(MARKETS_URL + "/" + ticker);
    }

    public String getMarketOrderbook(String ticker, RequestParameters params) {
        String paramsString = buildParams(params, "depth");
        return sendAuthorizedGet(MARKETS_URL + "/" + ticker + "/orderbook", paramsString);
    }

    public String getMarketOrderbook(String ticker) {
        return sendAuthorizedGet(MARKETS_URL + "/" + ticker + "/orderbook");
    }

    public String getSeries(String series_ticker) {
        return sendGet(SERIES_URL + "/" + series_ticker);
    }

    public String getMarketCandlesticks(String ticker, String series_ticker, Integer start_ts, Integer end_ts, Integer period_interval) {
        String paramsString = "start_ts=" + start_ts + "&end_ts=" + end_ts + "&period_interval=" + period_interval;
        return sendAuthorizedGet(SERIES_URL + "/" + series_ticker + "/markets/" + ticker + "/candlesticks", paramsString);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getKeyId() {
        return keyId;
    }

    public String signMessage(String message) throws Exception {
        if (privateKey == null) {
            throw new IllegalStateException("Kalshi private key is not configured.");
        }
        return Cryptography.signMessage(message, this.privateKey);
    }

    public boolean hasCredentials() {
        return keyId != null && !keyId.isBlank() && privateKey != null;
    }

    private String buildParams(RequestParameters params, String... allowedKeys) {
        if (params == null || params.getParams().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.getParams().entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (!isAllowed(key, allowedKeys) || val == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            String value = val instanceof String ? ((String) val).replace(" ", "") : String.valueOf(val);
            builder
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private boolean isAllowed(String key, String[] allowedKeys) {
        for (String allowedKey : allowedKeys) {
            if (allowedKey.equals(key)) {
                return true;
            }
        }
        return false;
    }

}
