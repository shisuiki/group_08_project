package edu.illinois.group8.messages;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OrderBookSnapshotMessage extends Message {
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
        return "OrderbookSnapshot{" +
                "type='" + getType() + '\'' +
                ", sid=" + getSid() +
                ", seq=" + seq +
                ", msg=" + msg +
                '}';
    }

    @Override
    public String getFormattedMessage() {
        return "{\n" + //
                "  \"type\": 'S',\n" + //
                "  \"symbol\": \"" + getMsg().getMarketTicker() + "\",\n" + //
                "  \"yes\": " + getMsg().getYes() + ",\n" + //
                "  \"no\": " + getMsg().getNo() + ",\n" + //
                "}";
    }

    public static class Msg {
        @JsonProperty("market_ticker")
        private String marketTicker;
        private List<List<Integer>> yes;
        private List<List<Integer>> no;

        public String getMarketTicker() {
            return marketTicker;
        }

        public List<List<Integer>> getYes() {
            return yes;
        }

        public List<List<Integer>> getNo() {
            return no;
        }

        @Override
        public String toString() {
            return "Msg{" +
                    "marketTicker='" + marketTicker + '\'' +
                    ", yes=" + yes +
                    ", no=" + no +
                    '}';
        }
    }
}
