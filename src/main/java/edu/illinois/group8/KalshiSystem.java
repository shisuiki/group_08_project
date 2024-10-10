package edu.illinois.group8;

import edu.illinois.group8.event.EventManager;
import edu.illinois.group8.wrapper.KalshiSession;

public class KalshiSystem {

    private static EventManager eventManager = new EventManager();
    private static KalshiSession instance;

    public static void main(String[] args) {
        instance = new KalshiSession("", "");
    }

    public static EventManager getEventManager() {
        return eventManager;
    }

    public static KalshiSession getInstance() {
        return instance;
    }
}
