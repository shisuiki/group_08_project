package edu.illinois.group8.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.FixedPoint;
import edu.illinois.group8.canonical.MarketLifecycleUpdate;
import edu.illinois.group8.canonical.MarketTrade;
import edu.illinois.group8.canonical.OpenInterestUpdate;
import edu.illinois.group8.canonical.OrderBookDeltaEvent;
import edu.illinois.group8.canonical.OrderBookSnapshotEvent;
import edu.illinois.group8.canonical.ParserErrorEvent;
import edu.illinois.group8.canonical.PriceLevel;
import edu.illinois.group8.canonical.RawSourceEvent;
import edu.illinois.group8.canonical.TickerUpdate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class KalshiCanonicalParser {
    private static final String SOURCE = "kalshi";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public CanonicalParseResult parseWebSocketMessage(String rawPayload) {
        return parseWebSocketMessage(rawPayload, System.nanoTime(), null);
    }

    public CanonicalParseResult parseWebSocketMessage(String rawPayload, long ingestTsNs) {
        return parseWebSocketMessage(rawPayload, ingestTsNs, null);
    }

    public CanonicalParseResult parseWebSocketMessage(String rawPayload, long ingestTsNs, String replayId) {
        String rawEventId = rawEventId(rawPayload);
        EventMetadata baseMetadata = new EventMetadata(
            SOURCE,
            null,
            null,
            null,
            null,
            null,
            null,
            ingestTsNs,
            null,
            rawEventId,
            replayId
        );
        RawSourceEvent rawEvent = new RawSourceEvent(rawEventId, baseMetadata, rawPayload);

        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String type = text(root, "type");
            JsonNode msg = root.path("msg");
            EventMetadata metadata = metadata(root, msg, rawEventId, ingestTsNs, replayId, type);
            List<CanonicalEvent> events = new ArrayList<>();

            switch (type == null ? "" : type) {
                case "trade" -> events.add(parseTrade(root, msg, metadata));
                case "ticker" -> events.addAll(parseTicker(root, msg, metadata));
                case "orderbook_snapshot" -> events.add(parseSnapshot(root, msg, metadata));
                case "orderbook_delta" -> events.add(parseDelta(root, msg, metadata));
                case "market_lifecycle_v2", "event_lifecycle" -> events.add(parseLifecycle(root, msg, metadata));
                case "error" -> events.add(parseKalshiError(root, msg, metadata, rawPayload));
                case "subscribed", "unsubscribed", "ok" -> {
                    return new CanonicalParseResult(rawEvent, List.of());
                }
                default -> events.add(parserError(rawEventId, metadata, "unsupported_message_type",
                    "Unsupported Kalshi message type: " + type, rawPayload));
            }
            return new CanonicalParseResult(rawEvent, events);
        } catch (Exception e) {
            ParserErrorEvent error = parserError(rawEventId, baseMetadata, "malformed_json", e.getMessage(), rawPayload);
            return new CanonicalParseResult(rawEvent, List.of(error));
        }
    }

    private MarketTrade parseTrade(JsonNode root, JsonNode msg, EventMetadata metadata) {
        String eventId = eventId(metadata.rawEventId(), "trade");
        return new MarketTrade(
            eventId,
            metadata,
            text(msg, "trade_id"),
            priceMicros(msg, "yes_price_dollars", "yes_price"),
            priceMicros(msg, "no_price_dollars", "no_price"),
            quantityMicros(msg, "count_fp", "count"),
            text(msg, "taker_side")
        );
    }

    private List<CanonicalEvent> parseTicker(JsonNode root, JsonNode msg, EventMetadata metadata) {
        List<CanonicalEvent> events = new ArrayList<>();
        events.add(new TickerUpdate(
            eventId(metadata.rawEventId(), "ticker"),
            metadata,
            firstPriceMicros(msg, "price_dollars", "last_price_dollars", "price"),
            priceMicros(msg, "yes_bid_dollars", "yes_bid"),
            priceMicros(msg, "yes_ask_dollars", "yes_ask"),
            quantityMicros(msg, "yes_bid_size_fp", null),
            quantityMicros(msg, "yes_ask_size_fp", null),
            quantityMicros(msg, "last_trade_size_fp", null),
            quantityMicros(msg, "volume_fp", "volume"),
            longValue(msg, "dollar_volume")
        ));

        if (hasAny(msg, "open_interest_fp", "open_interest", "dollar_open_interest")) {
            events.add(new OpenInterestUpdate(
                eventId(metadata.rawEventId(), "open_interest"),
                metadata,
                quantityMicros(msg, "open_interest_fp", "open_interest"),
                longValue(msg, "dollar_open_interest")
            ));
        }
        return events;
    }

    private OrderBookSnapshotEvent parseSnapshot(JsonNode root, JsonNode msg, EventMetadata metadata) {
        return new OrderBookSnapshotEvent(
            eventId(metadata.rawEventId(), "orderbook_snapshot"),
            metadata,
            parseLevels(msg, "yes_dollars_fp", "yes"),
            parseLevels(msg, "no_dollars_fp", "no")
        );
    }

    private OrderBookDeltaEvent parseDelta(JsonNode root, JsonNode msg, EventMetadata metadata) {
        String sourcePrice = firstText(msg, "price_dollars", "price");
        String sourceDelta = firstText(msg, "delta_fp", "delta");
        Long price = priceMicros(msg, "price_dollars", "price");
        Long delta = quantityMicros(msg, "delta_fp", "delta");
        if (price == null || delta == null) {
            throw new IllegalArgumentException("orderbook_delta missing price or delta");
        }
        return new OrderBookDeltaEvent(
            eventId(metadata.rawEventId(), "orderbook_delta"),
            metadata,
            text(msg, "side"),
            price,
            delta,
            sourcePrice,
            sourceDelta
        );
    }

    private MarketLifecycleUpdate parseLifecycle(JsonNode root, JsonNode msg, EventMetadata metadata) {
        return new MarketLifecycleUpdate(
            eventId(metadata.rawEventId(), "market_lifecycle"),
            metadata,
            firstText(msg, "event_type", "type"),
            longValue(msg, "open_ts"),
            longValue(msg, "close_ts"),
            booleanValue(msg, "fractional_trading_enabled"),
            text(msg, "price_level_structure"),
            msg.path("additional_metadata").isMissingNode() ? null : msg.path("additional_metadata")
        );
    }

    private ParserErrorEvent parseKalshiError(JsonNode root, JsonNode msg, EventMetadata metadata, String rawPayload) {
        String code = msg.path("code").isMissingNode() ? "kalshi_error" : "kalshi_error_" + msg.path("code").asText();
        return parserError(metadata.rawEventId(), metadata, code, text(msg, "msg"), rawPayload);
    }

    private EventMetadata metadata(
        JsonNode root,
        JsonNode msg,
        String rawEventId,
        long ingestTsNs,
        String replayId,
        String sourceChannel
    ) {
        Long eventTsMs = longValue(msg, "ts_ms");
        if (eventTsMs == null) {
            Long seconds = longValue(msg, "ts");
            if (seconds != null) {
                eventTsMs = seconds * 1000;
            } else {
                eventTsMs = instantTextToMs(firstText(msg, "time", "created_time"));
            }
        }

        return new EventMetadata(
            SOURCE,
            sourceChannel,
            longValue(root, "sid"),
            longValue(root, "seq"),
            firstText(msg, "market_ticker", "ticker"),
            text(msg, "market_id"),
            eventTsMs,
            ingestTsNs,
            null,
            rawEventId,
            replayId
        );
    }

    private List<PriceLevel> parseLevels(JsonNode msg, String dollarsField, String centsField) {
        JsonNode levels = msg.path(dollarsField);
        boolean dollars = !levels.isMissingNode() && levels.isArray();
        if (!dollars) {
            levels = msg.path(centsField);
        }
        if (!levels.isArray()) {
            return List.of();
        }

        List<PriceLevel> parsed = new ArrayList<>();
        for (JsonNode level : levels) {
            if (!level.isArray() || level.size() < 2) {
                continue;
            }
            String sourcePrice = level.get(0).asText();
            String sourceQuantity = level.get(1).asText();
            Long price = dollars
                ? FixedPoint.priceDollarsToMicros(sourcePrice)
                : FixedPoint.centsToPriceMicros(level.get(0).numberValue());
            Long quantity = dollars
                ? FixedPoint.quantityToMicros(sourceQuantity)
                : FixedPoint.quantityCountToMicros(level.get(1).numberValue());
            if (price != null && quantity != null && quantity > 0) {
                parsed.add(new PriceLevel(price, quantity, sourcePrice, sourceQuantity));
            }
        }
        return parsed;
    }

    private Long priceMicros(JsonNode node, String dollarsField, String centsField) {
        if (dollarsField != null && node.hasNonNull(dollarsField)) {
            return FixedPoint.priceDollarsToMicros(node.get(dollarsField).asText());
        }
        if (centsField != null && node.hasNonNull(centsField)) {
            return FixedPoint.centsToPriceMicros(node.get(centsField).numberValue());
        }
        return null;
    }

    private Long firstPriceMicros(JsonNode node, String firstDollars, String secondDollars, String centsField) {
        if (node.hasNonNull(firstDollars)) {
            return FixedPoint.priceDollarsToMicros(node.get(firstDollars).asText());
        }
        if (node.hasNonNull(secondDollars)) {
            return FixedPoint.priceDollarsToMicros(node.get(secondDollars).asText());
        }
        if (node.hasNonNull(centsField)) {
            return FixedPoint.centsToPriceMicros(node.get(centsField).numberValue());
        }
        return null;
    }

    private Long quantityMicros(JsonNode node, String fpField, String countField) {
        if (fpField != null && node.hasNonNull(fpField)) {
            return FixedPoint.quantityToMicros(node.get(fpField).asText());
        }
        if (countField != null && node.hasNonNull(countField)) {
            return FixedPoint.quantityCountToMicros(node.get(countField).numberValue());
        }
        return null;
    }

    private ParserErrorEvent parserError(
        String rawEventId,
        EventMetadata metadata,
        String code,
        String message,
        String rawPayload
    ) {
        return new ParserErrorEvent(
            eventId(rawEventId, "parser_error", code),
            metadata,
            code,
            message == null ? "" : message,
            rawPayload
        );
    }

    private static boolean hasAny(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean booleanValue(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asBoolean();
    }

    private static Long instantTextToMs(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String rawEventId(String rawPayload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawPayload.getBytes(StandardCharsets.UTF_8));
            return "raw_" + HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    public static String eventId(String rawEventId, String... parts) {
        StringBuilder builder = new StringBuilder(rawEventId);
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                builder.append('_').append(part.replaceAll("[^A-Za-z0-9_\\-]", "_"));
            }
        }
        return builder.toString();
    }
}
