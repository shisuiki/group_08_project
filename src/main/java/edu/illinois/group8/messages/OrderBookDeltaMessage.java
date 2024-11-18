package edu.illinois.group8.messages;

import com.fasterxml.jackson.annotation.JsonProperty;

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

    public static class Msg {
        @JsonProperty("market_ticker")
        private String marketTicker;
        private double price;
        private double delta;
        private String side;

        public String getMarketTicker() {
            return marketTicker;
        }

        public double getPrice() {
            return price;
        }

        public double getDelta() {
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
