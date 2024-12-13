package edu.illinois.group8.wrapper;

import java.util.AbstractMap;
import java.util.Map;
import java.util.TreeMap;

public class OrderBook {

    private TreeMap<Integer, Integer> bids; // price level -> bid size
    private TreeMap<Integer, Integer> asks;  // price level -> ask size

    public OrderBook() {
        this.bids = new TreeMap<>();
        this.asks = new TreeMap<>();
    }

    public void updateBook(String side, int price, int delta) {
        if (side.equals("yes")) {
            this.bids.put(price, this.bids.getOrDefault(price, 0) + delta);
        } else if (side.equals("no")) {
            this.asks.put(price, this.asks.getOrDefault(price, 0) + delta);
        } else {

        }
    }

    public Map.Entry<Integer, Integer> getBestBid() {
        return this.bids.isEmpty() ? new AbstractMap.SimpleEntry<>(0, 0) : this.bids.lastEntry();
    }

    public Map.Entry<Integer, Integer> getBestAsk() {
        return this.asks.isEmpty() ? new AbstractMap.SimpleEntry<>(100, 0) : this.asks.firstEntry();
    }

    public int[] getTopOfBook() {
        Map.Entry<Integer, Integer> bestBid = this.getBestBid();
        int bidPrice = bestBid.getKey();
        int bidSize = bestBid.getValue();

        Map.Entry<Integer, Integer> bestAsk = this.getBestAsk();
        int askPrice = bestAsk.getKey();
        int askSize = bestAsk.getValue();

        return new int[] { bidPrice, bidSize, askPrice, askSize };
    }

    public long getBidSize(int price) {
        return this.bids.getOrDefault(price, 0);
    }

    public long getAskSize(int price) {
        return this.asks.getOrDefault(price, 0);
    }

    public Map<Integer, Integer> getBids() {
        return this.bids;
    }

    public Map<Integer, Integer> getAsks() {
        return this.asks;
    }

    public void print() {
        System.out.println("Bid Size | Price | Ask Size");
        System.out.println("---------------------------");

        for (int i = 99; i > 0; i--) {
            long bid = this.bids.getOrDefault(i, 0);
            long ask = this.asks.getOrDefault(i, 0);
            if (bid == 0 && ask == 0) continue;
            System.out.printf("%-9d| %-6d| %d%n", bid, i, ask);
        }
    }

    @Override
    public String toString() {
        return "OrderBook{" +
                "bids=" + this.bids +
                ", asks=" + this.asks +
                '}';
    }

}
