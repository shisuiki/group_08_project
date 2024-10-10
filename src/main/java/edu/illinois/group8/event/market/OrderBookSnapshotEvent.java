package edu.illinois.group8.event.market;

import edu.illinois.group8.event.Event;
import org.json.simple.JSONObject;

public class OrderBookSnapshotEvent extends Event {

    public OrderBookSnapshotEvent(JSONObject data) {
        super(data);
    }

}
