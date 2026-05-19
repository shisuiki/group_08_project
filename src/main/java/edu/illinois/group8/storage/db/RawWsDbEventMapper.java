package edu.illinois.group8.storage.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

public final class RawWsDbEventMapper {
    private final ObjectMapper objectMapper;

    public RawWsDbEventMapper() {
        this(new ObjectMapper());
    }

    RawWsDbEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public RawWsDbEvent toDbEvent(RawWsDbEventInput input) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(input.source(), "input.source");
        Objects.requireNonNull(input.captureId(), "input.captureId");
        Objects.requireNonNull(input.connectionId(), "input.connectionId");
        Objects.requireNonNull(input.receiveWallTs(), "input.receiveWallTs");
        Objects.requireNonNull(input.rawPayload(), "input.rawPayload");

        RawMetadata metadata = extractMetadata(input.rawPayload());
        RawPayloadIdentity identity = rawPayloadIdentity(input.rawPayload());
        return new RawWsDbEvent(
            identity.rawEventId(),
            input.source(),
            input.captureId(),
            input.connectionId(),
            input.connectionSequence(),
            input.receiveTsNs(),
            input.receiveWallTs(),
            metadata.marketTicker(),
            metadata.sourceChannel(),
            metadata.sourceSequence(),
            identity.payloadSha256(),
            input.rawPayload(),
            input.ingestStatus()
        );
    }

    private RawMetadata extractMetadata(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode message = root.path("msg");
            return new RawMetadata(
                text(message, "market_ticker", "ticker"),
                text(root, "type"),
                sourceSequence(root.path("seq"))
            );
        } catch (Exception ignored) {
            return RawMetadata.empty();
        }
    }

    private static Long sourceSequence(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static RawPayloadIdentity rawPayloadIdentity(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payloadSha256 = HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
            return new RawPayloadIdentity("raw_" + payloadSha256.substring(0, 24), payloadSha256);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private record RawPayloadIdentity(String rawEventId, String payloadSha256) {
    }

    private record RawMetadata(String marketTicker, String sourceChannel, Long sourceSequence) {
        private static RawMetadata empty() {
            return new RawMetadata(null, null, null);
        }
    }
}
