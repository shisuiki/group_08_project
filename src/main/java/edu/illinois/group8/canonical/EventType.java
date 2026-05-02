package edu.illinois.group8.canonical;

public enum EventType {
    RAW_SOURCE_EVENT("raw_source_event", "raw.kalshi.websocket", 1),
    MARKET_TRADE("market_trade", "canonical.trade", 1),
    ORDER_BOOK_SNAPSHOT("orderbook_snapshot", "canonical.orderbook.snapshot", 1),
    ORDER_BOOK_DELTA("orderbook_delta", "canonical.orderbook.delta", 1),
    TICKER_UPDATE("ticker_update", "canonical.ticker", 1),
    OPEN_INTEREST_UPDATE("open_interest_update", "canonical.open_interest", 1),
    TOP_OF_BOOK_UPDATE("top_of_book_update", "derived.top_of_book", 1),
    MARKET_LIFECYCLE_UPDATE("market_lifecycle_update", "canonical.market_lifecycle", 1),
    PARSER_ERROR("parser_error", "system.parser_errors", 1),
    SEQUENCE_GAP("sequence_gap", "system.sequence_gaps", 1);

    private final String eventType;
    private final String streamName;
    private final int schemaVersion;

    EventType(String eventType, String streamName, int schemaVersion) {
        this.eventType = eventType;
        this.streamName = streamName;
        this.schemaVersion = schemaVersion;
    }

    public String eventType() {
        return eventType;
    }

    public String streamName() {
        return streamName;
    }

    public int schemaVersion() {
        return schemaVersion;
    }
}
