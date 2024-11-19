package edu.illinois.group8.wrapper;

import java.util.AbstractMap;
import java.util.Map;
import java.util.TreeMap;

public class OrderBook {

    private TreeMap<Integer, Long> bids; // price level -> bid size
    private TreeMap<Integer, Long> asks;  // price level -> ask size

    public OrderBook() {
        this.bids = new TreeMap<>();
        this.asks = new TreeMap<>();
    }

    public void updateBook(String side, int price, long delta) {
        if (side.equals("yes")) {
            this.bids.put(price, this.bids.getOrDefault(price, 0L) + delta);
        } else if (side.equals("no")) {
            this.asks.put(price, this.asks.getOrDefault(price, 0L) + delta);
        } else {

        }
    }

    public Map.Entry<Integer, Long> getBestBid() {
        return this.bids.isEmpty() ? new AbstractMap.SimpleEntry<>(0, 0L) : this.bids.lastEntry();
    }

    public Map.Entry<Integer, Long> getBestAsk() {
        return this.asks.isEmpty() ? new AbstractMap.SimpleEntry<>(100, 0L) : this.asks.firstEntry();
    }

    public long getBidSize(int price) {
        return this.bids.getOrDefault(price, 0L);
    }

    public long getAskSize(int price) {
        return this.asks.getOrDefault(price, 0L);
    }

    public Map<Integer, Long> getBids() {
        return this.bids;
    }

    public Map<Integer, Long> getAsks() {
        return this.asks;
    }

    public void print() {
        System.out.println("Bid Size | Price | Ask Size");
        System.out.println("---------------------------");

        for (int i = 99; i > 0; i--) {
            long bid = this.bids.getOrDefault(i, 0L);
            long ask = this.asks.getOrDefault(i, 0L);
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
