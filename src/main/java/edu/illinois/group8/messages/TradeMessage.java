package edu.illinois.group8.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

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
    public Map<String, Object> getFormattedMessage() {
        return Map.of(
            "type", "T",
            "symbol", msg.getMarketTicker(),
            "price", msg.getYesPrice(),
            "quantity", msg.getCount(),
            "side", (getMsg().getTakerSide().equals("no") ? "ask" : "bid"),
            "exchange_timestamp", msg.getTs() * 1000
        );
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
