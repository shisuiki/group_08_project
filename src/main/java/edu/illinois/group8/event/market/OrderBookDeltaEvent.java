package edu.illinois.group8.event.market;

import edu.illinois.group8.event.Event;
import org.json.simple.JSONObject;

public class OrderBookDeltaEvent extends Event {

    private int price;
    private long delta;
    private String side;

    public OrderBookDeltaEvent(JSONObject data) {
        super(data);
        this.price = ((Long) this.msg.get("price")).intValue();
        this.delta = (Long) this.msg.get("delta");
        this.side = (String) this.msg.get("side");
    }

    public int getPrice() {
        return price;
    }

    public long getDelta() {
        return delta;
    }

    public String getSide() {
        return side;
    }
}
