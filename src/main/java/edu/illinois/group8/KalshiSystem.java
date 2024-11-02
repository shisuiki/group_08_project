package edu.illinois.group8;

import edu.illinois.group8.event.EventManager;
import edu.illinois.group8.wrapper.KalshiSession;
import edu.illinois.group8.wrapper.KalshiWrapper;
import org.json.JSONObject;

public class KalshiSystem {

    private static EventManager eventManager = new EventManager();
    private static KalshiSession instance = new KalshiSession("", "");

    public static void main(String[] args) {
        KalshiWrapper wrapper = instance.getWrapper();
        System.out.println(wrapper.getMarkets());
    }

    public static EventManager getEventManager() {
        return eventManager;
    }

    public static KalshiSession getInstance() {
        return instance;
    }
}
