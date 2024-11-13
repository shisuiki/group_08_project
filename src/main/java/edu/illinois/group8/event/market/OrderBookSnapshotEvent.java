package edu.illinois.group8.event.market;

import edu.illinois.group8.event.Event;
import edu.illinois.group8.wrapper.OrderBook;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class OrderBookSnapshotEvent extends Event {

    private OrderBook orderBook;

    public OrderBookSnapshotEvent(JSONObject data) {
        super(data);
        this.orderBook = new OrderBook();
        if (this.msg.containsKey("yes")) {
            for (Object entry : (JSONArray) this.msg.get("yes")) {
                JSONArray level = (JSONArray) entry;
                int price = ((Long) level.get(0)).intValue();
                long quantity = (Long) level.get(1);
                this.orderBook.getBids().put(price, quantity);
            }
        }
        if (this.msg.containsKey("no")) {
            for (Object entry : (JSONArray) this.msg.get("no")) {
                JSONArray level = (JSONArray) entry;
                int price = 100 - ((Long) level.get(0)).intValue();
                long quantity = (Long) level.get(1);
                this.orderBook.getAsks().put(price, quantity);
            }
        }
    }

    public OrderBook getOrderBook() {
        return orderBook;
    }
}
