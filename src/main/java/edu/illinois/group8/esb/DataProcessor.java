package edu.illinois.group8.esb;

import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.messages.Message;
import edu.illinois.group8.messages.TradeMessage;
import edu.illinois.group8.messages.TickerMessage;
import edu.illinois.group8.messages.OrderBookDeltaMessage;
import edu.illinois.group8.messages.OrderBookSnapshotMessage;

import edu.illinois.group8.wrapper.OrderBook;
import io.aeron.Aeron;
import io.aeron.ConcurrentPublication;
import io.aeron.Publication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.BufferUtil;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DataProcessor {
    private final UnsafeBuffer buffer;
    private final ObjectMapper objectMapper;
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;

    private Map<String, OrderBook> orderBooks;

    public DataProcessor(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this.buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(512, 64));
        this.objectMapper = new ObjectMapper();
        this.communicationOrchestrator = communicationOrchestrator;
        this.orderBooks = new HashMap<>();
    }
    
    public void processMessage(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            String type = rootNode.get("type").asText();
            Message msg = null;
            ConcurrentPublication publication;

            switch (type) {
                case "orderbook_snapshot":
                    msg = objectMapper.readValue(message, OrderBookSnapshotMessage.class);
                    publication = communicationOrchestrator.getBookEventsPublication();
                    this.processSnapshot((OrderBookSnapshotMessage) msg);
                    break;
                case "orderbook_delta":
                    msg = objectMapper.readValue(message, OrderBookDeltaMessage.class);
                    publication = communicationOrchestrator.getBookEventsPublication();
                    this.processDelta((OrderBookDeltaMessage) msg);
                    break;
                case "ticker":
                    msg = objectMapper.readValue(message, TickerMessage.class);
                    publication = communicationOrchestrator.getTopOfBookPublication(); // TODO: Wrong publication; need to create a new type for ticker
                    break;
                case "trade":
                    msg = objectMapper.readValue(message, TradeMessage.class);
                    publication = communicationOrchestrator.getTradesPublication();
                    break;
                default:
                    System.out.println("Unknown message type: " + type);
                    publication = null;
            }

            if (msg != null) {
                publishMessage(msg.getFormattedMessage(), publication);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void publishMessage(String message, ConcurrentPublication internalDataChannel) {
        try {
            byte[] byte_msg = objectMapper.writeValueAsBytes(message);
            buffer.putBytes(0, byte_msg);
            internalDataChannel.offer(buffer, 0, byte_msg.length);
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
        orderBook.print();

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
        String formattedMessage = String.format(
                "{\"type\":'K',\"symbol\":\"%s\",\"bidPrice\":%d,\"bidSize\":%d,\"askPrice\":%d,\"askSize\":%d}",
                symbol, topOfBook[0], topOfBook[1], topOfBook[2], topOfBook[3]
        );
        publishMessage(formattedMessage, communicationOrchestrator.getTopOfBookPublication());
    }

}
