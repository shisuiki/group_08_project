package edu.illinois.group8.event.market;

import edu.illinois.group8.event.Event;
import org.json.simple.JSONObject;

public class TickerEvent extends Event {

    public TickerEvent(JSONObject data) {
        super(data);
    }

}
