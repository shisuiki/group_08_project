package edu.illinois.group8.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TickerMessage extends Message {
    private Msg msg;

    public Msg getMsg() {
        return msg;
    }

    @Override
    public String toString() {
        return "Ticker{" +
                "type='" + getType() + '\'' +
                ", sid=" + getSid() +
                ", msg=" + msg +
                '}';
    }

    @Override
    public Map<String, Object> getFormattedMessage() {
        return Map.of(
            "type", "R",
            "symbol", msg.getMarketTicker(),
            "price", msg.getPrice(),
            "bid", msg.getYesBid(),
            "ask", msg.getYesAsk(),
            "volume", msg.getVolume(),
            "exchange_timestamp", msg.getTs() * 1000
        );
    }

    public Map<String, Object> getOpenInterestMessage() {
        return Map.of(
            "type", "O",
            "symbol", msg.getMarketTicker(),
            "volume", msg.getVolume(),
            "open_interest", msg.getOpenInterest(),
            "dollar_volume", msg.getDollarVolume(),
            "dollar_open_interest", msg.getDollarOpenInterest()
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Msg {
        @JsonProperty("market_ticker")
        private String marketTicker;
        private int price;
        @JsonProperty("yes_bid")
        private int yesBid;
        @JsonProperty("yes_ask")
        private int yesAsk;
        private long volume;
        @JsonProperty("open_interest")
        private long openInterest;
        @JsonProperty("dollar_volume")
        private long dollarVolume;
        @JsonProperty("dollar_open_interest")
        private long dollarOpenInterest;
        private long ts;

        public String getMarketTicker() {
            return marketTicker;
        }

        public int getPrice() {
            return price;
        }

        public int getYesBid() {
            return yesBid;
        }

        public int getYesAsk() {
            return yesAsk;
        }

        public long getVolume() {
            return volume;
        }

        public long getOpenInterest() {
            return openInterest;
        }

        public long getDollarVolume() {
            return dollarVolume;
        }

        public long getDollarOpenInterest() {
            return dollarOpenInterest;
        }

        public long getTs() {
            return ts;
        }

        @Override
        public String toString() {
            return "Msg{" +
                    "marketTicker='" + marketTicker + '\'' +
                    ", price=" + price +
                    ", yesBid=" + yesBid +
                    ", yesAsk=" + yesAsk +
                    ", volume=" + volume +
                    ", openInterest=" + openInterest +
                    ", dollarVolume=" + dollarVolume +
                    ", dollarOpenInterest=" + dollarOpenInterest +
                    ", ts=" + ts +
                    '}';
        }
    }
}
