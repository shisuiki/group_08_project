package edu.illinois.group8.esb;

import edu.illinois.group8.messages.Message;
import edu.illinois.group8.messages.TradeMessage;
import edu.illinois.group8.messages.TickerMessage;
import edu.illinois.group8.messages.OrderBookDeltaMessage;
import edu.illinois.group8.messages.OrderBookSnapshotMessage;

import io.aeron.Aeron;
import io.aeron.Publication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.BufferUtil;

public class DataProcessor {
    private Aeron aeron;
    private Publication internalDataChannel;
    private final UnsafeBuffer buffer;
    private final ObjectMapper objectMapper;
    
    public DataProcessor(String internalChannel) {
        aeron = Aeron.connect(new Aeron.Context());
        internalDataChannel = aeron.addPublication(internalChannel, 1);
        
        this.buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(512, 64));
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
            internalDataChannel.offer(buffer, 0, byte_msg.length);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
