package edu.illinois.group8.event.market;

import edu.illinois.group8.event.Event;
import org.json.simple.JSONObject;

public class OrderBookDeltaEvent extends Event {

    private int price;
    private int delta;
    private String side;

    public OrderBookDeltaEvent(JSONObject data) {
        super(data);
        this.price = (int) data.get("price");
        this.delta = (int) data.get("delta");
        this.side = (String) data.get("side");
    }

    public int getPrice() {
        return price;
    }

    public int getDelta() {
        return delta;
    }

    public String getSide() {
        return side;
    }
}
