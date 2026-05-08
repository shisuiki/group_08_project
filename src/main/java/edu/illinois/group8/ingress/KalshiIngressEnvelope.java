package edu.illinois.group8.ingress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

import java.io.IOException;
import java.time.Instant;

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
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Kalshi ingress envelope", e);
        }
    }

    public static KalshiIngressEnvelope parse(String wirePayload, long fallbackReceiveTsNs) {
        try {
            JsonNode root = MAPPER.readTree(wirePayload);
            JsonNode rawPayload = root.path("raw_payload");
            if (INGRESS_TYPE.equals(root.path("ingress_type").asText()) && rawPayload.isTextual()) {
                return new KalshiIngressEnvelope(
                    rawPayload.asText(),
                    root.path("receive_ts_ns").isNumber() ? root.path("receive_ts_ns").asLong() : fallbackReceiveTsNs,
                    root.path("receive_wall_ts").asText(null),
                    root.path("connection_id").asText(null),
                    root.path("replay_id").asText(null),
                    true
                );
            }
        } catch (IOException ignored) {
        }
        return new KalshiIngressEnvelope(wirePayload, fallbackReceiveTsNs, null, null, null, false);
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
