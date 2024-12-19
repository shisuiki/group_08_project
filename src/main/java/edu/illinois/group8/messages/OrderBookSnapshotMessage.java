package edu.illinois.group8.messages;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
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
    public Map<String, Object> getFormattedMessage() {
        return Map.of(
            "type", "S",
            "symbol", msg.getMarketTicker(),
            "bid", msg.getYes(),
            "ask", msg.getNo().stream().map(innerList -> innerList.stream().map(i -> 100 - i)
                        .collect(Collectors.toList()))
                        .collect(Collectors.toList())
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Msg {
        @JsonProperty("market_ticker")
        private String marketTicker;
        private List<List<Integer>> yes = new ArrayList<>();
        private List<List<Integer>> no = new ArrayList<>();

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
