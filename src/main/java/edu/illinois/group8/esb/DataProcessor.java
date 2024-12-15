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

import org.agrona.BufferUtil;
import org.agrona.ExpandableArrayBuffer;

public class DataProcessor {
    private final ExpandableArrayBuffer buffer;
    private final ObjectMapper objectMapper;
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;

    public DataProcessor(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this.buffer = new ExpandableArrayBuffer();
        this.objectMapper = new ObjectMapper();
        this.communicationOrchestrator = communicationOrchestrator;
    }
    
    public void processMessage(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            String type = rootNode.get("type").asText();
            Message msg = null;

            switch (type) {
                case "orderbook_snapshot":
                    System.out.println("data processor: received orderbook snapshot message");
                    msg = objectMapper.readValue(message, OrderBookSnapshotMessage.class);
                    break;
                case "orderbook_delta":
                    System.out.println("data processor: received orderbook delta message");
                    msg = objectMapper.readValue(message, OrderBookDeltaMessage.class);
                    break;
                case "ticker":
                    System.out.println("data processor: received ticker message");
                    msg = objectMapper.readValue(message, TickerMessage.class);
                    break;
                case "trade":
                    System.out.println("data processor: received trade message");
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

    public void publishMessage(String message) {
        try {
            byte[] byte_msg = objectMapper.writeValueAsBytes(message);
            buffer.putBytes(0, byte_msg);
            communicationOrchestrator.getInternalPublication().offer(buffer, 0, byte_msg.length);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
