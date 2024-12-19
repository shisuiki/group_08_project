package edu.illinois.group8.wrapper;

public class KalshiSession {
    private KalshiWrapper wrapper;
    private KalshiWebSocketClient wsClient;

    public KalshiSession(String baseUrl, String keyId, String path) {
        this.wrapper = new KalshiWrapper(baseUrl, keyId, path);
        this.wsClient = new KalshiWebSocketClient(this.wrapper);
    }

    public KalshiWrapper getWrapper() {
        return wrapper;
    }

    public KalshiWebSocketClient getWsClient() {
        return wsClient;
    }
}
