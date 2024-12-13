package edu.illinois.group8.esb;

import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.messages.Message;
import edu.illinois.group8.messages.TradeMessage;
import edu.illinois.group8.messages.TickerMessage;
import edu.illinois.group8.messages.OrderBookDeltaMessage;
import edu.illinois.group8.messages.OrderBookSnapshotMessage;

import io.aeron.Aeron;
import io.aeron.ConcurrentPublication;
import io.aeron.Publication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.BufferUtil;
import org.agrona.ExpandableArrayBuffer;

public class DataProcessor {
    private final ExpandableArrayBuffer buffer;
    private final ObjectMapper objectMapper;
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;

    public DataProcessor(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        // this.buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(512, 64));
        this.buffer = new ExpandableArrayBuffer();
        this.objectMapper = new ObjectMapper();
        this.communicationOrchestrator = communicationOrchestrator;
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
                    break;
                case "orderbook_delta":
                    msg = objectMapper.readValue(message, OrderBookDeltaMessage.class);
                    publication = communicationOrchestrator.getTopOfBookPublication();
                    break;
                case "ticker":
                    msg = objectMapper.readValue(message, TickerMessage.class);
                    publication = communicationOrchestrator.getTopOfBookPublication();
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
}
