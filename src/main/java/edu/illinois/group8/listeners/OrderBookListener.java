package edu.illinois.group8.listeners;

import edu.illinois.group8.event.EventHandler;
import edu.illinois.group8.event.Listener;
import edu.illinois.group8.event.market.OrderBookDeltaEvent;
import edu.illinois.group8.event.market.OrderBookSnapshotEvent;

public class OrderBookListener implements Listener {

    @EventHandler
    public void onSnapshot(OrderBookSnapshotEvent event) {

    }

    @EventHandler
    public void onDelta(OrderBookDeltaEvent event) {

    }

}
