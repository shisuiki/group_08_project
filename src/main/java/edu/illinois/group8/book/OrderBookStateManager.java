package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.OrderBookDeltaEvent;
import edu.illinois.group8.canonical.OrderBookSnapshotEvent;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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

    public boolean pauseForSnapshotRecovery(CanonicalEvent event) {
        if (event instanceof OrderBookDeltaEvent delta) {
            book(delta.metadata().marketTicker()).pauseForSnapshotRecovery();
            return true;
        }
        return false;
    }

    public void pauseAllForSnapshotRecovery() {
        booksByMarket.values().forEach(OrderBookState::pauseForSnapshotRecovery);
    }

    public List<OrderBookRecoveryCheckpoint> recoveryCheckpoints() {
        return booksByMarket.values().stream()
            .map(OrderBookState::recoveryCheckpoint)
            .toList();
    }

    public void restorePaused(Collection<OrderBookRecoveryCheckpoint> checkpoints) {
        if (checkpoints == null) {
            throw new IllegalArgumentException("Order book recovery checkpoints must not be null.");
        }
        Map<String, OrderBookState> restoredBooks = new HashMap<>();
        for (OrderBookRecoveryCheckpoint checkpoint : checkpoints) {
            validateCheckpoint(checkpoint);
            String marketTicker = checkpoint.marketTicker();
            if (restoredBooks.put(marketTicker, OrderBookState.restoredPaused(checkpoint)) != null) {
                throw new IllegalArgumentException("Duplicate order book recovery checkpoint: " + marketTicker);
            }
        }
        booksByMarket.clear();
        booksByMarket.putAll(restoredBooks);
    }

    public OrderBookState getState(String marketTicker) {
        return booksByMarket.get(marketTicker);
    }

    private OrderBookState book(String marketTicker) {
        String key = marketTicker == null ? "" : marketTicker;
        return booksByMarket.computeIfAbsent(key, OrderBookState::new);
    }

    private static void validateCheckpoint(OrderBookRecoveryCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("Order book recovery checkpoint must not be null.");
        }
        String marketTicker = checkpoint.marketTicker();
        if (marketTicker == null || marketTicker.isBlank()) {
            throw new IllegalArgumentException("Order book recovery checkpoint market ticker must not be blank.");
        }
        Long lastSequence = checkpoint.lastSequence();
        if (lastSequence != null && lastSequence < 0L) {
            throw new IllegalArgumentException("Order book recovery checkpoint sequence must not be negative.");
        }
    }
}
