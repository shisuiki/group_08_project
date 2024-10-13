package edu.illinois.group8.event.market;

import edu.illinois.group8.event.Event;
import org.json.simple.JSONObject;

public class OrderBookDeltaEvent extends Event {

    public OrderBookDeltaEvent(JSONObject data) {
        super(data);
    }

}
