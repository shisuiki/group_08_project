package edu.illinois.group8.wrapper;

import edu.illinois.group8.utils.WebSocketClient;

import java.util.Map;

public class KalshiWebSocketClient extends WebSocketClient {
    public KalshiWebSocketClient(String url, String path, Map<String, String> headers) {
        super(url, path, headers);
    }

    @Override
    public void onOpen() {
        System.out.println("Connection opened.");
    }

    @Override
    public void onMessage(String message) {
        System.out.println("Received message: " + message);
    }

    @Override
    public void onError(Exception e) {
        System.err.println("An error occurred: " + e.getMessage());
    }

    @Override
    public void onClose() {
        System.out.println("Connection closed.");
    }
}

