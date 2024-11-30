package edu.illinois.group8.messages;

import java.util.List;
import java.util.stream.Collectors;

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
                "  \"bid\": " + getMsg().getYes() + ",\n" + //
                "  \"ask\": " + getMsg().getNo().stream()
                                    .map(innerList -> innerList.stream().map(i -> 100 - i)
                                        .collect(Collectors.toList()))
                                    .collect(Collectors.toList()) + "\n" + //
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
