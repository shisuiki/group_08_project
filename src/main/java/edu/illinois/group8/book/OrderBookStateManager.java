package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.OrderBookDeltaEvent;
import edu.illinois.group8.canonical.OrderBookSnapshotEvent;

import java.util.HashMap;
import java.util.Map;

public class OrderBookStateManager {
    private final Map<String, OrderBookState> booksByMarket = new HashMap<>();

    public BookUpdateResult apply(CanonicalEvent event) {
        if (event instanceof OrderBookSnapshotEvent snapshot) {
            return book(snapshot.metadata().marketTicker()).applySnapshot(snapshot);
        }
        if (event instanceof OrderBookDeltaEvent delta) {
            return book(delta.metadata().marketTicker()).applyDelta(delta);
        }
        return BookUpdateResult.empty();
    }

    public OrderBookState getState(String marketTicker) {
        return booksByMarket.get(marketTicker);
    }

    private OrderBookState book(String marketTicker) {
        String key = marketTicker == null ? "" : marketTicker;
        return booksByMarket.computeIfAbsent(key, OrderBookState::new);
    }
}
