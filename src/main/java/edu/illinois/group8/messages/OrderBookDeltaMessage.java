package edu.illinois.group8.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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
    public String getFormattedMessage() {
        if (getMsg().getSide().equals("yes")) {
            return "{\n" + //
                "  \"type\": \"D\",\n" + //
                "  \"symbol\": \"" + getMsg().getMarketTicker() + "\",\n" + //
                "  \"price\": " + getMsg().getPrice() + ",\n" + //
                "  \"delta\": " + getMsg().getDelta() + ",\n" + //
                "  \"side\": \"bid\"\n" + //
                "}";
        } else {
            return "{\n" + //
                "  \"type\": \"D\",\n" + //
                "  \"symbol\": \"" + getMsg().getMarketTicker() + "\",\n" + //
                "  \"price\": " + (100 - getMsg().getPrice()) + ",\n" + //
                "  \"delta\": " + (-1 * getMsg().getDelta()) + ",\n" + //
                "  \"side\": \"ask\"\n" + //
                "}";
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
