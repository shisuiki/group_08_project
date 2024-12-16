package edu.illinois.group8.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
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

    @Override
    public String getFormattedMessage() {
        return "{\n" + //
                "  \"type\": \"T\",\n" + //
                "  \"symbol\": \"" + getMsg().getMarketTicker() + "\",\n" + //
                "  \"price\": " + getMsg().getYesPrice() + ",\n" + //
                "  \"quantity\": " + getMsg().getCount() + ",\n" + //
                "  \"side\": \"" + (getMsg().getTakerSide().equals("no") ? "ask" : "bid") + "\",\n" + //
                "  \"exchange_timestamp\": " + getMsg().getTs() * 1000 + "\n" + //
                "}";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Msg {
        @JsonProperty("market_ticker")
        private String marketTicker;
        @JsonProperty("yes_price")
        private int yesPrice;
        @JsonProperty("no_price")
        private int noPrice;
        private int count;
        @JsonProperty("taker_side")
        private String takerSide;
        private long ts;

        public String getMarketTicker() {
            return marketTicker;
        }

        public int getYesPrice() {
            return yesPrice;
        }

        public int getNoPrice() {
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
