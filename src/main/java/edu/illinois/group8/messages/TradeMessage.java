package edu.illinois.group8.messages;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TradeMessage extends Message {
    private Msg msg;

    public Msg getMsg() {
        return msg;
    }

    @Override
    public String toString() {
        return "Trade{" +
                "type='" + getType() + '\'' +
                ", sid=" + getSid() +
                ", msg=" + msg +
                '}';
    }

    public static class Msg {
        @JsonProperty("market_ticker")
        private String marketTicker;
        @JsonProperty("yes_price")
        private double yesPrice;
        @JsonProperty("no_price")
        private double noPrice;
        private int count;
        @JsonProperty("taker_side")
        private String takerSide;
        private long ts;

        public String getMarketTicker() {
            return marketTicker;
        }

        public double getYesPrice() {
            return yesPrice;
        }

        public double getNoPrice() {
            return noPrice;
        }

        public int getCount() {
            return count;
        }

        public String getTakerSide() {
            return takerSide;
        }

        public long getTs() {
            return ts;
        }

        @Override
        public String toString() {
            return "Msg{" +
                    "marketTicker='" + marketTicker + '\'' +
                    ", yesPrice=" + yesPrice +
                    ", noPrice=" + noPrice +
                    ", count=" + count +
                    ", takerSide='" + takerSide + '\'' +
                    ", ts=" + ts +
                    '}';
        }
    }
}
