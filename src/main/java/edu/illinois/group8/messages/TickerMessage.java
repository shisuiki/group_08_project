package edu.illinois.group8.messages;

import com.fasterxml.jackson.annotation.JsonProperty;

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
    public String getFormattedMessage() {
        return "{\n" + //
                "  \"type\": 'K',\n" + //
                "  \"symbol\": \"" + getMsg().getMarketTicker() + "\",\n" + //
                "  \"price\": " + getMsg().getPrice() + ",\n" + //
                "  \"bid\": " + getMsg().getYesBid() + ",\n" + //
                "  \"ask\": " + getMsg().getYesAsk() + ",\n" + //
                "  \"active_contracts\": " + getMsg().getOpenInterest() + ",\n" + //
                "  \"exchange_timestamp\": " + getMsg().getTs() * 1000 + "\n" + //
                "}";
    }

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
