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
    private static final int BINARY_MAGIC = 0x4b494531; // KIE1
    private static final int NULL_STRING_LENGTH = -1;
    private static final int FIXED_BINARY_HEADER_LENGTH = Integer.BYTES + Long.BYTES;

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
        byte[] receiveWallTsBytes = optionalStringBytes(receiveWallTs == null ? null : receiveWallTs.toString());
        byte[] connectionIdBytes = optionalStringBytes(blankToNull(connectionId));
        byte[] replayIdBytes = optionalStringBytes(blankToNull(replayId));
        byte[] rawPayloadBytes = Objects.requireNonNull(rawPayload, "rawPayload").getBytes(StandardCharsets.UTF_8);
        int totalLength = FIXED_BINARY_HEADER_LENGTH
            + encodedStringLength(receiveWallTsBytes)
            + encodedStringLength(connectionIdBytes)
            + encodedStringLength(replayIdBytes)
            + encodedStringLength(rawPayloadBytes);
        byte[] bytes = new byte[totalLength];
        int offset = 0;
        offset = putInt(bytes, offset, BINARY_MAGIC);
        offset = putLong(bytes, offset, receiveTsNs);
        offset = putString(bytes, offset, receiveWallTsBytes);
        offset = putString(bytes, offset, connectionIdBytes);
        offset = putString(bytes, offset, replayIdBytes);
        putString(bytes, offset, rawPayloadBytes);
        return bytes;
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
        KalshiIngressEnvelope binaryEnvelope = parseBinaryEnvelope(
            wirePayloadBytes,
            offset,
            length
        );
        if (binaryEnvelope != null) {
            return binaryEnvelope;
        }
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

    private static KalshiIngressEnvelope parseBinaryEnvelope(
        byte[] bytes,
        int offset,
        int length
    ) {
        if (length < Integer.BYTES || getInt(bytes, offset) != BINARY_MAGIC) {
            return null;
        }
        try {
            int end = offset + length;
            int cursor = offset + Integer.BYTES;
            if (end - cursor < Long.BYTES) {
                return null;
            }
            long receiveTsNs = getLong(bytes, cursor);
            cursor += Long.BYTES;

            StringReadResult receiveWallTs = readNullableString(bytes, cursor, end);
            cursor = receiveWallTs.nextOffset();
            StringReadResult connectionId = readNullableString(bytes, cursor, end);
            cursor = connectionId.nextOffset();
            StringReadResult replayId = readNullableString(bytes, cursor, end);
            cursor = replayId.nextOffset();
            StringReadResult rawPayload = readRequiredString(bytes, cursor, end);
            cursor = rawPayload.nextOffset();
            if (cursor != end) {
                return null;
            }
            return new KalshiIngressEnvelope(
                rawPayload.value(),
                receiveTsNs,
                receiveWallTs.value(),
                connectionId.value(),
                replayId.value(),
                true
            );
        } catch (IllegalArgumentException exc) {
            return null;
        }
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

    private static byte[] optionalStringBytes(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int encodedStringLength(byte[] bytes) {
        return Integer.BYTES + (bytes == null ? 0 : bytes.length);
    }

    private static int putString(byte[] target, int offset, byte[] value) {
        if (value == null) {
            return putInt(target, offset, NULL_STRING_LENGTH);
        }
        offset = putInt(target, offset, value.length);
        System.arraycopy(value, 0, target, offset, value.length);
        return offset + value.length;
    }

    private static int putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
        return offset + Integer.BYTES;
    }

    private static int putLong(byte[] target, int offset, long value) {
        target[offset] = (byte) (value >>> 56);
        target[offset + 1] = (byte) (value >>> 48);
        target[offset + 2] = (byte) (value >>> 40);
        target[offset + 3] = (byte) (value >>> 32);
        target[offset + 4] = (byte) (value >>> 24);
        target[offset + 5] = (byte) (value >>> 16);
        target[offset + 6] = (byte) (value >>> 8);
        target[offset + 7] = (byte) value;
        return offset + Long.BYTES;
    }

    private static int getInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
            | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }

    private static long getLong(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 56)
            | ((long) (bytes[offset + 1] & 0xff) << 48)
            | ((long) (bytes[offset + 2] & 0xff) << 40)
            | ((long) (bytes[offset + 3] & 0xff) << 32)
            | ((long) (bytes[offset + 4] & 0xff) << 24)
            | ((long) (bytes[offset + 5] & 0xff) << 16)
            | ((long) (bytes[offset + 6] & 0xff) << 8)
            | (long) (bytes[offset + 7] & 0xff);
    }

    private static StringReadResult readNullableString(byte[] bytes, int offset, int end) {
        int stringLength = readLength(bytes, offset, end);
        int valueOffset = offset + Integer.BYTES;
        if (stringLength == NULL_STRING_LENGTH) {
            return new StringReadResult(null, valueOffset);
        }
        return readStringValue(bytes, valueOffset, end, stringLength);
    }

    private static StringReadResult readRequiredString(byte[] bytes, int offset, int end) {
        int stringLength = readLength(bytes, offset, end);
        if (stringLength < 0) {
            throw new IllegalArgumentException("required string length must be non-negative");
        }
        return readStringValue(bytes, offset + Integer.BYTES, end, stringLength);
    }

    private static int readLength(byte[] bytes, int offset, int end) {
        if (end - offset < Integer.BYTES) {
            throw new IllegalArgumentException("length prefix is truncated");
        }
        int stringLength = getInt(bytes, offset);
        if (stringLength < NULL_STRING_LENGTH) {
            throw new IllegalArgumentException("invalid string length");
        }
        return stringLength;
    }

    private static StringReadResult readStringValue(byte[] bytes, int offset, int end, int stringLength) {
        if (end - offset < stringLength) {
            throw new IllegalArgumentException("string value is truncated");
        }
        return new StringReadResult(
            new String(bytes, offset, stringLength, StandardCharsets.UTF_8),
            offset + stringLength
        );
    }

    private record StringReadResult(String value, int nextOffset) {
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
