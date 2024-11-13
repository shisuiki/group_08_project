package edu.illinois.group8.listeners;

import edu.illinois.group8.event.EventHandler;
import edu.illinois.group8.event.Listener;
import edu.illinois.group8.event.market.OrderBookDeltaEvent;
import edu.illinois.group8.event.market.OrderBookSnapshotEvent;
import edu.illinois.group8.wrapper.KalshiSession;
import edu.illinois.group8.wrapper.OrderBook;

public class OrderBookListener implements Listener {

    private KalshiSession instance;

    public OrderBookListener(KalshiSession instance) {
        this.instance = instance;
    }

    @EventHandler
    public void onSnapshot(OrderBookSnapshotEvent event) {
        System.out.println("Received snapshot for " + event.getMarketTicker());
        this.instance.getOrderBooks().put(event.getMarketTicker(), event.getOrderBook());
        event.getOrderBook().print();
    }

    @EventHandler
    public void onDelta(OrderBookDeltaEvent event) {
        System.out.println("Received delta for " + event.getMarketTicker());
        OrderBook orderBook = this.instance.getOrderBooks().get(event.getMarketTicker());
        orderBook.updateBook(event.getSide(), event.getPrice(), event.getDelta());
        orderBook.print();
    }

}
