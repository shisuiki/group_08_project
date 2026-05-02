package edu.illinois.group8.canonical;

public final class CanonicalEvents {
    private CanonicalEvents() {
    }

    public static CanonicalEvent withPublishTsNs(CanonicalEvent event, long publishTsNs) {
        EventMetadata metadata = event.metadata().withPublishTsNs(publishTsNs);
        if (event instanceof RawSourceEvent value) {
            return new RawSourceEvent(value.eventId(), metadata, value.rawPayload());
        }
        if (event instanceof MarketTrade value) {
            return new MarketTrade(
                value.eventId(),
                metadata,
                value.tradeId(),
                value.yesPriceMicros(),
                value.noPriceMicros(),
                value.quantityMicros(),
                value.takerSide()
            );
        }
        if (event instanceof OrderBookSnapshotEvent value) {
            return new OrderBookSnapshotEvent(value.eventId(), metadata, value.yesBids(), value.noBids());
        }
        if (event instanceof OrderBookDeltaEvent value) {
            return new OrderBookDeltaEvent(
                value.eventId(),
                metadata,
                value.side(),
                value.priceMicros(),
                value.deltaQuantityMicros(),
                value.sourcePrice(),
                value.sourceDelta()
            );
        }
        if (event instanceof TickerUpdate value) {
            return new TickerUpdate(
                value.eventId(),
                metadata,
                value.priceMicros(),
                value.yesBidMicros(),
                value.yesAskMicros(),
                value.yesBidQuantityMicros(),
                value.yesAskQuantityMicros(),
                value.lastTradeQuantityMicros(),
                value.volumeMicros(),
                value.dollarVolume()
            );
        }
        if (event instanceof OpenInterestUpdate value) {
            return new OpenInterestUpdate(value.eventId(), metadata, value.openInterestMicros(), value.dollarOpenInterest());
        }
        if (event instanceof TopOfBookUpdate value) {
            return new TopOfBookUpdate(
                value.eventId(),
                metadata,
                value.bidPriceMicros(),
                value.bidQuantityMicros(),
                value.askPriceMicros(),
                value.askQuantityMicros(),
                value.crossed()
            );
        }
        if (event instanceof MarketLifecycleUpdate value) {
            return new MarketLifecycleUpdate(
                value.eventId(),
                metadata,
                value.lifecycleEventType(),
                value.openTs(),
                value.closeTs(),
                value.fractionalTradingEnabled(),
                value.priceLevelStructure(),
                value.additionalMetadata()
            );
        }
        if (event instanceof ParserErrorEvent value) {
            return new ParserErrorEvent(
                value.eventId(),
                metadata,
                value.errorCode(),
                value.errorMessage(),
                value.rawPayload()
            );
        }
        if (event instanceof SequenceGapEvent value) {
            return new SequenceGapEvent(
                value.eventId(),
                metadata,
                value.expectedSequence(),
                value.actualSequence(),
                value.reason(),
                value.recoveryAction()
            );
        }
        return event;
    }
}
