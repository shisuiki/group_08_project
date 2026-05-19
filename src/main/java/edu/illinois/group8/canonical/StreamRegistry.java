package edu.illinois.group8.canonical;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StreamRegistry {
    /*
     * These values are Aeron stream IDs on AERON_EXTERNAL_CHANNEL. They are not
     * Kalshi market IDs, tickers, or contract universe identifiers.
     */
    public static final int RAW_KALSHI_WEBSOCKET = 10;
    public static final int CANONICAL_TRADE = 11;
    public static final int CANONICAL_ORDERBOOK_SNAPSHOT = 12;
    public static final int CANONICAL_ORDERBOOK_DELTA = 13;
    public static final int CANONICAL_TICKER = 14;
    public static final int CANONICAL_OPEN_INTEREST = 15;
    public static final int DERIVED_TOP_OF_BOOK = 16;
    public static final int CANONICAL_MARKET_LIFECYCLE = 17;
    public static final int SYSTEM_PARSER_ERRORS = 18;
    public static final int SYSTEM_SEQUENCE_GAPS = 19;
    public static final int INTERNAL_CANONICAL = 20;

    private static final Map<String, StreamContract> STREAMS = new LinkedHashMap<>();

    static {
        register(EventType.RAW_SOURCE_EVENT, RAW_KALSHI_WEBSOCKET, "DB raw ingest; NDJSON/S3 explicit capture/export", true);
        register(EventType.MARKET_TRADE, CANONICAL_TRADE, "DB canonical; NDJSON/S3 explicit capture/export", true);
        register(EventType.ORDER_BOOK_SNAPSHOT, CANONICAL_ORDERBOOK_SNAPSHOT, "DB canonical; NDJSON/S3 explicit capture/export", true);
        register(EventType.ORDER_BOOK_DELTA, CANONICAL_ORDERBOOK_DELTA, "DB canonical; NDJSON/S3 explicit capture/export", true);
        register(EventType.TICKER_UPDATE, CANONICAL_TICKER, "DB canonical; NDJSON/S3 explicit capture/export", true);
        register(EventType.OPEN_INTEREST_UPDATE, CANONICAL_OPEN_INTEREST, "DB canonical; NDJSON/S3 explicit capture/export", true);
        register(EventType.TOP_OF_BOOK_UPDATE, DERIVED_TOP_OF_BOOK, "DB canonical; NDJSON/S3 explicit capture/export", true);
        register(EventType.MARKET_LIFECYCLE_UPDATE, CANONICAL_MARKET_LIFECYCLE, "DB canonical; NDJSON/S3 explicit capture/export", true);
        register(EventType.PARSER_ERROR, SYSTEM_PARSER_ERRORS, "DB canonical; NDJSON/S3 explicit capture/export", true);
        register(EventType.SEQUENCE_GAP, SYSTEM_SEQUENCE_GAPS, "DB canonical; NDJSON/S3 explicit capture/export", true);
    }

    private StreamRegistry() {
    }

    public static Optional<StreamContract> byName(String streamName) {
        return Optional.ofNullable(STREAMS.get(streamName));
    }

    public static Collection<StreamContract> all() {
        return STREAMS.values();
    }

    public static List<StreamContract> externalStreams() {
        return STREAMS.values().stream()
            .filter(StreamContract::external)
            .toList();
    }

    public static List<StreamContract> normalizedStreams() {
        return STREAMS.values().stream()
            .filter(StreamContract::external)
            .filter(stream -> !EventType.RAW_SOURCE_EVENT.streamName().equals(stream.streamName()))
            .toList();
    }

    private static void register(EventType eventType, int streamId, String retentionPolicy, boolean external) {
        STREAMS.put(eventType.streamName(), new StreamContract(
            eventType.streamName(),
            streamId,
            eventType.schemaVersion(),
            "core-backend",
            "json",
            "Per source subscription sequence where Kalshi provides seq; otherwise ingest order.",
            true,
            retentionPolicy,
            external
        ));
    }
}
