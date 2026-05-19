package edu.illinois.group8.ingress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

public final class KalshiIngressEnvelope {
    private static final ObjectMapper MAPPER = new JsonCanonicalSerializer().mapper();
    private static final String INGRESS_TYPE = "kalshi_websocket";

    private final String rawPayload;
    private final long receiveTsNs;
    private final String receiveWallTs;
    private final String connectionId;
    private final String replayId;
    private final boolean enveloped;

    private KalshiIngressEnvelope(
        String rawPayload,
        long receiveTsNs,
        String receiveWallTs,
        String connectionId,
        String replayId,
        boolean enveloped
    ) {
        this.rawPayload = rawPayload;
        this.receiveTsNs = receiveTsNs;
        this.receiveWallTs = receiveWallTs;
        this.connectionId = connectionId;
        this.replayId = replayId;
        this.enveloped = enveloped;
    }

    public static String wrap(
        String rawPayload,
        long receiveTsNs,
        Instant receiveWallTs,
        String connectionId,
        String replayId
    ) {
        ObjectNode node = envelopeNode(rawPayload, receiveTsNs, receiveWallTs, connectionId, replayId);
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Kalshi ingress envelope", e);
        }
    }

    public static byte[] wrapBytes(
        String rawPayload,
        long receiveTsNs,
        Instant receiveWallTs,
        String connectionId,
        String replayId
    ) {
        ObjectNode node = envelopeNode(rawPayload, receiveTsNs, receiveWallTs, connectionId, replayId);
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Kalshi ingress envelope", e);
        }
    }

    private static ObjectNode envelopeNode(
        String rawPayload,
        long receiveTsNs,
        Instant receiveWallTs,
        String connectionId,
        String replayId
    ) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("schema_version", 1);
        node.put("ingress_type", INGRESS_TYPE);
        node.put("receive_ts_ns", receiveTsNs);
        if (receiveWallTs != null) {
            node.put("receive_wall_ts", receiveWallTs.toString());
        }
        if (connectionId != null && !connectionId.isBlank()) {
            node.put("connection_id", connectionId);
        }
        if (replayId != null && !replayId.isBlank()) {
            node.put("replay_id", replayId);
        }
        node.put("raw_payload", rawPayload);
        return node;
    }

    public static KalshiIngressEnvelope parse(String wirePayload, long fallbackReceiveTsNs) {
        try {
            JsonNode root = MAPPER.readTree(wirePayload);
            KalshiIngressEnvelope envelope = parseEnvelope(root, fallbackReceiveTsNs);
            if (envelope != null) {
                return envelope;
            }
        } catch (IOException ignored) {
        }
        return new KalshiIngressEnvelope(wirePayload, fallbackReceiveTsNs, null, null, null, false);
    }

    public static KalshiIngressEnvelope parse(byte[] wirePayloadBytes, long fallbackReceiveTsNs) {
        return parse(wirePayloadBytes, 0, wirePayloadBytes.length, fallbackReceiveTsNs);
    }

    public static KalshiIngressEnvelope parse(
        byte[] wirePayloadBytes,
        int offset,
        int length,
        long fallbackReceiveTsNs
    ) {
        Objects.checkFromIndexSize(offset, length, wirePayloadBytes.length);
        try {
            JsonNode root = MAPPER.readTree(wirePayloadBytes, offset, length);
            KalshiIngressEnvelope envelope = parseEnvelope(root, fallbackReceiveTsNs);
            if (envelope != null) {
                return envelope;
            }
        } catch (IOException ignored) {
        }
        return new KalshiIngressEnvelope(
            new String(wirePayloadBytes, offset, length, StandardCharsets.UTF_8),
            fallbackReceiveTsNs,
            null,
            null,
            null,
            false
        );
    }

    private static KalshiIngressEnvelope parseEnvelope(JsonNode root, long fallbackReceiveTsNs) {
        JsonNode rawPayload = root.path("raw_payload");
        if (!INGRESS_TYPE.equals(root.path("ingress_type").asText()) || !rawPayload.isTextual()) {
            return null;
        }
        return new KalshiIngressEnvelope(
            rawPayload.asText(),
            root.path("receive_ts_ns").isNumber() ? root.path("receive_ts_ns").asLong() : fallbackReceiveTsNs,
            root.path("receive_wall_ts").asText(null),
            root.path("connection_id").asText(null),
            root.path("replay_id").asText(null),
            true
        );
    }

    public String rawPayload() {
        return rawPayload;
    }

    public long receiveTsNs() {
        return receiveTsNs;
    }

    public String receiveWallTs() {
        return receiveWallTs;
    }

    public String connectionId() {
        return connectionId;
    }

    public String replayId() {
        return replayId;
    }

    public boolean enveloped() {
        return enveloped;
    }
}
