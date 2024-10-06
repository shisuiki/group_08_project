package edu.illinois.group8.wrapper;

public class KalshiWrapper {

    private String keyId, privateKey, baseUrl;

    public KalshiWrapper(String keyId, String keyPath) {
        this(keyId, keyPath, false);
    }

    public KalshiWrapper(String keyId, String keyPath, boolean useDemo) {
        this.keyId = keyId;
        this.privateKey = "todo";
        this.baseUrl = useDemo ? "https://demo-api.kalshi.com" : "https://trading-api.kalshi.com";
    }

}
