package edu.illinois.group8.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.FixedPoint;
import edu.illinois.group8.canonical.MarketLifecycleUpdate;
import edu.illinois.group8.canonical.MarketTrade;
import edu.illinois.group8.canonical.OpenInterestUpdate;
import edu.illinois.group8.canonical.OrderBookSnapshotEvent;
import edu.illinois.group8.canonical.ParserErrorEvent;
import edu.illinois.group8.canonical.PriceLevel;
import edu.illinois.group8.canonical.RawSourceEvent;
import edu.illinois.group8.canonical.TickerUpdate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class KalshiRestParser {
    private static final String SOURCE = "kalshi";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public CanonicalParseResult parseTradesResponse(String rawPayload, long ingestTsNs) {
        return parseRestResponse(rawPayload, ingestTsNs, "rest.trades", this::parseTrades);
    }

    public CanonicalParseResult parseMarketsResponse(String rawPayload, long ingestTsNs) {
        return parseRestResponse(rawPayload, ingestTsNs, "rest.markets", this::parseMarkets);
    }

    public CanonicalParseResult parseMarketOrderbookResponse(String ticker, String rawPayload, long ingestTsNs) {
        return parseRestResponse(rawPayload, ingestTsNs, "rest.orderbook", (root, rawEventId, ignoredIngestTsNs) ->
            parseOrderbook(ticker, root, rawEventId, ingestTsNs));
    }

    public CanonicalParseResult parseCandlesticksResponse(String rawPayload, long ingestTsNs) {
        return parseRestResponse(rawPayload, ingestTsNs, "rest.candlesticks", this::parseCandlesticks);
    }

    private CanonicalParseResult parseRestResponse(
        String rawPayload,
        long ingestTsNs,
        String channel,
        RestEventParser parser
    ) {
        String rawEventId = KalshiCanonicalParser.rawEventId(rawPayload);
        EventMetadata metadata = new EventMetadata(
            SOURCE,
            channel,
            null,
            null,
            null,
            null,
            null,
            ingestTsNs,
            null,
            rawEventId,
            null
        );
        RawSourceEvent raw = new RawSourceEvent(rawEventId, metadata, rawPayload);
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            return new CanonicalParseResult(raw, parser.parse(root, rawEventId, ingestTsNs));
        } catch (Exception e) {
            ParserErrorEvent error = new ParserErrorEvent(
                KalshiCanonicalParser.eventId(rawEventId, "rest_parser_error"),
                metadata,
                "rest_parse_error",
                e.getMessage(),
                rawPayload
            );
            return new CanonicalParseResult(raw, List.of(error));
        }
    }

    private List<CanonicalEvent> parseTrades(JsonNode root, String rawEventId, long ingestTsNs) {
        List<CanonicalEvent> events = new ArrayList<>();
        JsonNode trades = root.path("trades");
        if (!trades.isArray()) {
            return events;
        }
        int index = 0;
        for (JsonNode trade : trades) {
            EventMetadata metadata = metadata(
                "rest.trades",
                rawEventId,
                firstText(trade, "ticker", "market_ticker"),
                text(trade, "market_id"),
                instantMs(text(trade, "created_time")),
                ingestTsNs
            );
            events.add(new MarketTrade(
                KalshiCanonicalParser.eventId(rawEventId, "rest_trade", firstText(trade, "trade_id", Integer.toString(index))),
                metadata,
                text(trade, "trade_id"),
                priceMicros(trade, "yes_price_dollars", "yes_price"),
                priceMicros(trade, "no_price_dollars", "no_price"),
                quantityMicros(trade, "count_fp", "count"),
                text(trade, "taker_side")
            ));
            index++;
        }
        return events;
    }

    private List<CanonicalEvent> parseMarkets(JsonNode root, String rawEventId, long ingestTsNs) {
        List<CanonicalEvent> events = new ArrayList<>();
        JsonNode markets = root.path("markets");
        if (!markets.isArray()) {
            return events;
        }
        int index = 0;
        for (JsonNode market : markets) {
            String ticker = text(market, "ticker");
            Long updatedMs = instantMs(firstText(market, "updated_time", "created_time"));
            EventMetadata metadata = metadata("rest.markets", rawEventId, ticker, text(market, "market_id"), updatedMs, ingestTsNs);
            events.add(new TickerUpdate(
                KalshiCanonicalParser.eventId(rawEventId, "rest_market_ticker", ticker == null ? Integer.toString(index) : ticker),
                metadata,
                priceMicros(market, "last_price_dollars", "last_price"),
                priceMicros(market, "yes_bid_dollars", "yes_bid"),
                priceMicros(market, "yes_ask_dollars", "yes_ask"),
                quantityMicros(market, "yes_bid_size_fp", null),
                quantityMicros(market, "yes_ask_size_fp", null),
                null,
                quantityMicros(market, "volume_fp", "volume"),
                null
            ));
            events.add(new OpenInterestUpdate(
                KalshiCanonicalParser.eventId(rawEventId, "rest_market_open_interest", ticker == null ? Integer.toString(index) : ticker),
                metadata,
                quantityMicros(market, "open_interest_fp", "open_interest"),
                null
            ));
            events.add(new MarketLifecycleUpdate(
                KalshiCanonicalParser.eventId(rawEventId, "rest_market_lifecycle", ticker == null ? Integer.toString(index) : ticker),
                metadata,
                text(market, "status"),
                instantSeconds(text(market, "open_time")),
                instantSeconds(text(market, "close_time")),
                booleanValue(market, "fractional_trading_enabled"),
                text(market, "price_level_structure"),
                market
            ));
            index++;
        }
        return events;
    }

    private List<CanonicalEvent> parseOrderbook(String ticker, JsonNode root, String rawEventId, long ingestTsNs) {
        JsonNode orderbook = root.path("orderbook_fp");
        EventMetadata metadata = new EventMetadata(
            SOURCE,
            "rest.orderbook",
            null,
            null,
            ticker,
            null,
            null,
            ingestTsNs,
            null,
            rawEventId,
            null
        );
        return List.of(new OrderBookSnapshotEvent(
            KalshiCanonicalParser.eventId(rawEventId, "rest_orderbook_snapshot", ticker),
            metadata,
            parseLevels(orderbook.path("yes_dollars")),
            parseLevels(orderbook.path("no_dollars"))
        ));
    }

    private List<CanonicalEvent> parseCandlesticks(JsonNode root, String rawEventId, long ingestTsNs) {
        String ticker = text(root, "ticker");
        JsonNode candles = root.path("candlesticks");
        if (!candles.isArray()) {
            return List.of();
        }
        List<CanonicalEvent> events = new ArrayList<>();
        int index = 0;
        for (JsonNode candle : candles) {
            Long eventTsMs = longValue(candle, "end_period_ts") == null ? null : longValue(candle, "end_period_ts") * 1000;
            EventMetadata metadata = metadata("rest.candlesticks", rawEventId, ticker, null, eventTsMs, ingestTsNs);
            events.add(new TickerUpdate(
                KalshiCanonicalParser.eventId(rawEventId, "rest_candlestick", Integer.toString(index)),
                metadata,
                null,
                nestedPrice(candle, "yes_bid", "close_dollars"),
                nestedPrice(candle, "yes_ask", "close_dollars"),
                null,
                null,
                null,
                quantityMicros(candle, "volume_fp", "volume"),
                null
            ));
            if (candle.hasNonNull("open_interest_fp")) {
                events.add(new OpenInterestUpdate(
                    KalshiCanonicalParser.eventId(rawEventId, "rest_candlestick_open_interest", Integer.toString(index)),
                    metadata,
                    quantityMicros(candle, "open_interest_fp", "open_interest"),
                    null
                ));
            }
            index++;
        }
        return events;
    }

    private EventMetadata metadata(
        String channel,
        String rawEventId,
        String marketTicker,
        String marketId,
        Long eventTsMs,
        long ingestTsNs
    ) {
        return new EventMetadata(SOURCE, channel, null, null, marketTicker, marketId, eventTsMs, ingestTsNs, null, rawEventId, null);
    }

    private List<PriceLevel> parseLevels(JsonNode levels) {
        if (!levels.isArray()) {
            return List.of();
        }
        List<PriceLevel> parsed = new ArrayList<>();
        for (JsonNode level : levels) {
            if (!level.isArray() || level.size() < 2) {
                continue;
            }
            String price = level.get(0).asText();
            String quantity = level.get(1).asText();
            parsed.add(new PriceLevel(
                FixedPoint.priceDollarsToMicros(price),
                FixedPoint.quantityToMicros(quantity),
                price,
                quantity
            ));
        }
        return parsed;
    }

    private Long nestedPrice(JsonNode node, String objectField, String priceField) {
        JsonNode child = node.path(objectField);
        if (!child.hasNonNull(priceField)) {
            return null;
        }
        return FixedPoint.priceDollarsToMicros(child.get(priceField).asText());
    }

    private Long priceMicros(JsonNode node, String dollarsField, String centsField) {
        if (node.hasNonNull(dollarsField)) {
            return FixedPoint.priceDollarsToMicros(node.get(dollarsField).asText());
        }
        if (node.hasNonNull(centsField) && node.get(centsField).isNumber()) {
            return FixedPoint.centsToPriceMicros(node.get(centsField).numberValue());
        }
        return null;
    }

    private Long quantityMicros(JsonNode node, String fpField, String countField) {
        if (node.hasNonNull(fpField)) {
            return FixedPoint.quantityToMicros(node.get(fpField).asText());
        }
        if (countField != null && node.hasNonNull(countField) && node.get(countField).isNumber()) {
            return FixedPoint.quantityCountToMicros(node.get(countField).numberValue());
        }
        return null;
    }

    private Long longValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : null;
    }

    private Boolean booleanValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asBoolean() : null;
    }

    private Long instantMs(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value).toEpochMilli();
    }

    private Long instantSeconds(String value) {
        Long ms = instantMs(value);
        return ms == null ? null : ms / 1000;
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private interface RestEventParser {
        List<CanonicalEvent> parse(JsonNode root, String rawEventId, long ingestTsNs);
    }
}
