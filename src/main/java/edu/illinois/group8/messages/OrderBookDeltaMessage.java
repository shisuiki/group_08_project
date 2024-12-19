package edu.illinois.group8.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderBookDeltaMessage extends Message {
    private int seq;
    private Msg msg;

    public int getSeq() {
        return seq;
    }

    public Msg getMsg() {
        return msg;
    }

    @Override
    public String toString() {
        return "OrderbookDelta{" +
                "type='" + getType() + '\'' +
                ", sid=" + getSid() +
                ", seq=" + seq +
                ", msg=" + msg +
                '}';
    }

    @Override
    public Map<String, Object> getFormattedMessage() {
        if (msg.getSide().equals("yes")) {
            return Map.of(
                "type", "D",
                "symbol", msg.getMarketTicker(),
                "price", msg.getPrice(),
                "delta", msg.getDelta(),
                "side", "bid"
            );
        } else {
            return Map.of(
                "type", "D",
                "symbol", msg.getMarketTicker(),
                "price", (100 - msg.getPrice()),
                "delta", (-1 * msg.getDelta()),
                "side", "ask"
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Msg {
        @JsonProperty("market_ticker")
        private String marketTicker;
        private int price;
        private int delta;
        private String side;

        public String getMarketTicker() {
            return marketTicker;
        }

        public int getPrice() {
            return price;
        }

        public int getDelta() {
            return delta;
        }

        public String getSide() {
            return side;
        }

        @Override
        public String toString() {
            return "Msg{" +
                    "marketTicker='" + marketTicker + '\'' +
                    ", price=" + price +
                    ", delta=" + delta +
                    ", side='" + side + '\'' +
                    '}';
        }
    }
}
