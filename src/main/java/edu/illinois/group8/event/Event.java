package edu.illinois.group8.event;

import org.json.simple.JSONObject;

public abstract class Event {

    protected JSONObject data;

    protected String type;
    protected long commandId, subscriptionId, sequenceNum;
    protected String marketTicker;
    protected JSONObject msg;

    @SuppressWarnings("unchecked")
    protected Event(JSONObject data) {
        this.data = data;
        this.type = (String) data.get("type");
        this.commandId = (long) data.getOrDefault("id", 0L);
        this.subscriptionId = (long) data.getOrDefault("sid", 0L);
        this.sequenceNum = (long) data.getOrDefault("seq", 0L);
        this.msg = (JSONObject) data.get("msg");
        this.marketTicker = (String) (this.msg.get("market_ticker"));
    }

    public String getMarketTicker() {
        return marketTicker;
    }

}
