package edu.illinois.group8.wrapper;

public class KalshiWrapper {

    private String baseUrl;

    public KalshiWrapper() {
        this(false);
    }

    public KalshiWrapper(boolean useDemo) {
        this.baseUrl = useDemo ? "https://demo-api.kalshi.com" : "https://trading-api.kalshi.com";
    }

}
