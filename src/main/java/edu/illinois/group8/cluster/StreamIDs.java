package edu.illinois.group8.cluster;

public enum StreamIDs {
    TRADE_IDX(0),
    TOP_OF_BOOK_IDX(1),
    BOOK_EVENTS_IDX(2),
    TICKER_IDX(3),
    OPEN_INTEREST_IDX(4);
    
    private final int value;

    StreamIDs(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
