package edu.illinois.group8.esb;

import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.messages.Message;
import edu.illinois.group8.messages.TradeMessage;
import edu.illinois.group8.messages.TickerMessage;
import edu.illinois.group8.messages.OrderBookDeltaMessage;
import edu.illinois.group8.messages.OrderBookSnapshotMessage;

import edu.illinois.group8.wrapper.OrderBook;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.agrona.ExpandableArrayBuffer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DataProcessor {
    private final ExpandableArrayBuffer buffer;
    private final ObjectMapper objectMapper;
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;

    private Map<String, OrderBook> orderBooks;

    public DataProcessor(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this.buffer = new ExpandableArrayBuffer();
        this.objectMapper = new ObjectMapper();
        this.communicationOrchestrator = communicationOrchestrator;
        this.orderBooks = new HashMap<>();
    }
    
    public void processMessage(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            String type = rootNode.get("type").asText();
            Message msg = null;

            // System.out.println("ESB received message: " + message);

            switch (type) {
                case "orderbook_snapshot":
                    msg = objectMapper.readValue(message, OrderBookSnapshotMessage.class);
                    this.processSnapshot((OrderBookSnapshotMessage) msg);
                    break;
                case "orderbook_delta":
                    msg = objectMapper.readValue(message, OrderBookDeltaMessage.class);
                    this.processDelta((OrderBookDeltaMessage) msg);
                    break;
                case "ticker":
                    msg = objectMapper.readValue(message, TickerMessage.class);
                    publishMessage(((TickerMessage) msg).getOpenInterestMessage());
                    break;
                case "trade":
                    msg = objectMapper.readValue(message, TradeMessage.class);
                    break;
                default:
                    System.out.println("Unknown message type: " + type);
            }

            if (msg != null) {
                publishMessage(msg.getFormattedMessage());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void publishMessage(Map<String, Object> message) {
        try {
            byte[] byte_msg = objectMapper.writeValueAsBytes(message);
            buffer.putBytes(0, byte_msg);
            long result = communicationOrchestrator.getInternalPublication().offer(buffer, 0, byte_msg.length);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processSnapshot(OrderBookSnapshotMessage msg) {
        OrderBookSnapshotMessage.Msg snapshot = msg.getMsg();
        String marketTicker = snapshot.getMarketTicker();
        OrderBook orderBook = new OrderBook();
        for (List<Integer> level : snapshot.getYes()) {
            int price = level.get(0);
            int quantity = level.get(1);
            orderBook.getBids().put(price, quantity);
        }
        for (List<Integer> level : snapshot.getNo()) {
            int price = 100 - level.get(0);
            int quantity = level.get(1);
            orderBook.getAsks().put(price, quantity);
        }
        orderBooks.put(marketTicker, orderBook);
        this.sendTopOfBook(marketTicker, orderBook);
    }

    private void processDelta(OrderBookDeltaMessage msg) {
        OrderBookDeltaMessage.Msg delta = msg.getMsg();
        String marketTicker = delta.getMarketTicker();
        OrderBook orderBook = orderBooks.get(marketTicker);

        int[] topOfBookPre = orderBook.getTopOfBook();

        orderBook.updateBook(delta.getSide(), delta.getPrice(), delta.getDelta());

        if (!Arrays.equals(topOfBookPre, orderBook.getTopOfBook()))
            sendTopOfBook(marketTicker, orderBook);
    }

    private void sendTopOfBook(String symbol, OrderBook orderBook) {
        int[] topOfBook = orderBook.getTopOfBook();

        Map<String, Object> formattedMessage = Map.of(
            "type", "K",
            "symbol", symbol,
            "bidPrice", topOfBook[0],
            "bidSize", topOfBook[1],
            "askPrice", topOfBook[2],
            "askSize", topOfBook[3]
        );

        publishMessage(formattedMessage);
    }
}
