package edu.illinois.group8.event.market;

import edu.illinois.group8.event.Event;
import org.json.simple.JSONObject;

public class TradeEvent extends Event {

    public TradeEvent(JSONObject data) {
        super(data);
    }

}
