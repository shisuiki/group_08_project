package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.FixedPoint;
import edu.illinois.group8.canonical.OrderBookDeltaEvent;
import edu.illinois.group8.canonical.OrderBookSnapshotEvent;
import edu.illinois.group8.canonical.PriceLevel;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.canonical.TopOfBookUpdate;
import edu.illinois.group8.parser.KalshiCanonicalParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OrderBookState {
    private final String marketTicker;
    private final TreeMap<Long, Long> yesBids = new TreeMap<>();
    private final TreeMap<Long, Long> noBids = new TreeMap<>();

    private boolean hasSnapshot;
    private boolean pausedForRecovery;
    private Long lastSequence;
    private TopOfBook lastTopOfBook;

    public OrderBookState(String marketTicker) {
        this.marketTicker = marketTicker;
    }

    public BookUpdateResult applySnapshot(OrderBookSnapshotEvent snapshot) {
        yesBids.clear();
        noBids.clear();
        snapshot.yesBids().forEach(level -> putLevel(yesBids, level));
        snapshot.noBids().forEach(level -> putLevel(noBids, level));
        hasSnapshot = true;
        pausedForRecovery = false;
        lastSequence = snapshot.metadata().sourceSequence();
        return changedTopOfBookEvents(snapshot.eventId(), snapshot.metadata());
    }

    public BookUpdateResult applyDelta(OrderBookDeltaEvent delta) {
        List<CanonicalEvent> generated = new ArrayList<>();
        Long actualSequence = delta.metadata().sourceSequence();

        if (!hasSnapshot || pausedForRecovery) {
            generated.add(sequenceGap(
                delta.eventId(),
                delta.metadata(),
                expectedNextSequence(),
                actualSequence,
                !hasSnapshot ? "delta_before_snapshot" : "market_paused_for_snapshot_recovery"
            ));
            return new BookUpdateResult(generated);
        }

        Long expectedSequence = expectedNextSequence();
        if (expectedSequence != null && actualSequence != null && !expectedSequence.equals(actualSequence)) {
            pausedForRecovery = true;
            generated.add(sequenceGap(delta.eventId(), delta.metadata(), expectedSequence, actualSequence, "out_of_order_delta"));
            return new BookUpdateResult(generated);
        }

        applyLevelDelta(delta);
        if (actualSequence != null) {
            lastSequence = actualSequence;
        }

        generated.addAll(changedTopOfBookEvents(delta.eventId(), delta.metadata()).generatedEvents());
        TopOfBook current = currentTopOfBook();
        if (current.crossed()) {
            generated.add(sequenceGap(
                delta.eventId(),
                delta.metadata(),
                expectedNextSequence(),
                actualSequence,
                "crossed_book"
            ));
        }
        return new BookUpdateResult(generated);
    }

    public TopOfBook currentTopOfBook() {
        Map.Entry<Long, Long> bestYesBid = yesBids.isEmpty() ? null : yesBids.lastEntry();
        Map.Entry<Long, Long> bestNoBid = noBids.isEmpty() ? null : noBids.lastEntry();

        long bidPrice = bestYesBid == null ? 0L : bestYesBid.getKey();
        long bidQuantity = bestYesBid == null ? 0L : bestYesBid.getValue();
        long askPrice = bestNoBid == null ? FixedPoint.PRICE_SCALE : FixedPoint.PRICE_SCALE - bestNoBid.getKey();
        long askQuantity = bestNoBid == null ? 0L : bestNoBid.getValue();
        boolean crossed = bidQuantity > 0 && askQuantity > 0 && bidPrice >= askPrice;
        return new TopOfBook(bidPrice, bidQuantity, askPrice, askQuantity, crossed);
    }

    public boolean hasSnapshot() {
        return hasSnapshot;
    }

    public boolean pausedForRecovery() {
        return pausedForRecovery;
    }

    public Long lastSequence() {
        return lastSequence;
    }

    private BookUpdateResult changedTopOfBookEvents(String sourceEventId, EventMetadata metadata) {
        TopOfBook current = currentTopOfBook();
        if (current.equals(lastTopOfBook)) {
            return BookUpdateResult.empty();
        }
        lastTopOfBook = current;
        return new BookUpdateResult(List.of(new TopOfBookUpdate(
            KalshiCanonicalParser.eventId(sourceEventId, "top_of_book"),
            metadata,
            current.bidPriceMicros(),
            current.bidQuantityMicros(),
            current.askPriceMicros(),
            current.askQuantityMicros(),
            current.crossed()
        )));
    }

    private void applyLevelDelta(OrderBookDeltaEvent delta) {
        TreeMap<Long, Long> bookSide = "no".equals(delta.side()) ? noBids : yesBids;
        long updatedQuantity = bookSide.getOrDefault(delta.priceMicros(), 0L) + delta.deltaQuantityMicros();
        if (updatedQuantity <= 0L) {
            bookSide.remove(delta.priceMicros());
        } else {
            bookSide.put(delta.priceMicros(), updatedQuantity);
        }
    }

    private void putLevel(TreeMap<Long, Long> side, PriceLevel level) {
        if (level.quantityMicros() > 0L) {
            side.put(level.priceMicros(), level.quantityMicros());
        }
    }

    private Long expectedNextSequence() {
        return lastSequence == null ? null : lastSequence + 1;
    }

    private SequenceGapEvent sequenceGap(
        String sourceEventId,
        EventMetadata metadata,
        Long expected,
        Long actual,
        String reason
    ) {
        return new SequenceGapEvent(
            KalshiCanonicalParser.eventId(sourceEventId, "sequence_gap", reason),
            metadata,
            expected,
            actual,
            reason,
            "pause_market_and_request_fresh_snapshot"
        );
    }
}
