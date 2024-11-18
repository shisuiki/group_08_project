package edu.illinois.group8.esb;

import edu.illinois.group8.messages.Message;
import edu.illinois.group8.messages.TradeMessage;
import edu.illinois.group8.messages.TickerMessage;
import edu.illinois.group8.messages.OrderBookDeltaMessage;
import edu.illinois.group8.messages.OrderBookSnapshotMessage;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DataProcessor {
    private final ObjectMapper objectMapper;
    
    public DataProcessor() {
        this.objectMapper = new ObjectMapper();
    }

    public void processMessage(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);

            String type = rootNode.get("type").asText();

            Message msg = null;

            switch (type) {
                case "orderbook_snapshot":
                    msg = objectMapper.readValue(message, OrderBookSnapshotMessage.class);
                    break;
                case "orderbook_delta":
                    msg = objectMapper.readValue(message, OrderBookDeltaMessage.class);
                    break;
                case "ticker":
                    msg = objectMapper.readValue(message, TickerMessage.class);
                    break;
                case "trade":
                    msg = objectMapper.readValue(message, TradeMessage.class);
                    break;
                default:
                    System.out.println("Unknown message type: " + type);
            }

            if (message != null) {
                System.out.println("Deserialized message: " + message);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
