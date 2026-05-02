package edu.illinois.group8.cluster;

import edu.illinois.group8.canonical.StreamRegistry;

public enum StreamIDs {
    RAW_KALSHI_WEBSOCKET(StreamRegistry.RAW_KALSHI_WEBSOCKET),
    TRADE_IDX(StreamRegistry.CANONICAL_TRADE),
    TOP_OF_BOOK_IDX(StreamRegistry.DERIVED_TOP_OF_BOOK),
    BOOK_SNAPSHOT_IDX(StreamRegistry.CANONICAL_ORDERBOOK_SNAPSHOT),
    BOOK_DELTA_IDX(StreamRegistry.CANONICAL_ORDERBOOK_DELTA),
    BOOK_EVENTS_IDX(StreamRegistry.CANONICAL_ORDERBOOK_DELTA),
    INTERNAL_IDX(StreamRegistry.INTERNAL_CANONICAL),
    TICKER_IDX(StreamRegistry.CANONICAL_TICKER),
    OPEN_INTEREST_IDX(StreamRegistry.CANONICAL_OPEN_INTEREST),
    MARKET_LIFECYCLE_IDX(StreamRegistry.CANONICAL_MARKET_LIFECYCLE),
    PARSER_ERRORS_IDX(StreamRegistry.SYSTEM_PARSER_ERRORS),
    SEQUENCE_GAPS_IDX(StreamRegistry.SYSTEM_SEQUENCE_GAPS);

    private final int value;

    StreamIDs(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
