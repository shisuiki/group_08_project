package edu.illinois.group8.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.storage.db.MarketMetadata;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class MarketMetadataMapper {
    private final ObjectMapper mapper;

    public MarketMetadataMapper() {
        this(new JsonCanonicalSerializer().mapper());
    }

    MarketMetadataMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<MarketMetadata> fromMarketsResponse(String rawPayload) {
        JsonNode root = readRoot(rawPayload);
        JsonNode markets = root.path("markets");
        if (!markets.isArray()) {
            return List.of();
        }

        List<MarketMetadata> metadata = new ArrayList<>();
        for (JsonNode market : markets) {
            if (!market.isObject()) {
                continue;
            }
            String ticker = firstText(market, "ticker", "market_ticker");
            if (ticker == null || ticker.isBlank()) {
                continue;
            }
            metadata.add(new MarketMetadata(
                ticker,
                text(market, "event_ticker"),
                text(market, "series_ticker"),
                text(market, "status"),
                instant(market, "open_time", ticker),
                instant(market, "close_time", ticker),
                instant(market, "settlement_time", ticker),
                optionalObjectOrArrayPayload(market, "rules", "rulebook"),
                compactJson(market)
            ));
        }
        return metadata;
    }

    private JsonNode readRoot(String rawPayload) {
        try {
            return mapper.readTree(rawPayload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid markets response JSON", e);
        }
    }

    private Instant instant(JsonNode market, String fieldName, String ticker) {
        String value = text(market, fieldName);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "Invalid " + fieldName + " timestamp for market " + ticker + ": " + value,
                e
            );
        }
    }

    private String optionalObjectOrArrayPayload(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && (value.isObject() || value.isArray())) {
                return compactJson(value);
            }
        }
        return null;
    }

    private String compactJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize market metadata JSON", e);
        }
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = text(node, fieldName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text.isBlank() ? null : text;
    }
}
