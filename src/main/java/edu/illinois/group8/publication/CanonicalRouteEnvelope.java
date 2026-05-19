package edu.illinois.group8.publication;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class CanonicalRouteEnvelope {
    static final int MAGIC = 0x43524831; // CRH1
    private static final int INT_BYTES = Integer.BYTES;

    private CanonicalRouteEnvelope() {
    }

    public static byte[] wrap(String streamName, byte[] payload) {
        Objects.requireNonNull(streamName, "streamName");
        Objects.requireNonNull(payload, "payload");
        if (streamName.isBlank()) {
            throw new IllegalArgumentException("streamName must not be blank");
        }

        byte[] streamNameBytes = streamName.getBytes(StandardCharsets.UTF_8);
        int length = Math.addExact(
            Math.addExact(INT_BYTES + INT_BYTES, streamNameBytes.length),
            Math.addExact(INT_BYTES, payload.length)
        );
        byte[] envelope = new byte[length];
        int cursor = 0;
        writeInt(envelope, cursor, MAGIC);
        cursor += INT_BYTES;
        writeInt(envelope, cursor, streamNameBytes.length);
        cursor += INT_BYTES;
        System.arraycopy(streamNameBytes, 0, envelope, cursor, streamNameBytes.length);
        cursor += streamNameBytes.length;
        writeInt(envelope, cursor, payload.length);
        cursor += INT_BYTES;
        System.arraycopy(payload, 0, envelope, cursor, payload.length);
        return envelope;
    }

    public static ParseResult parse(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length < INT_BYTES || readInt(bytes, offset) != MAGIC) {
            return ParseResult.legacyResult();
        }

        int end = offset + length;
        int cursor = offset + INT_BYTES;
        if (remaining(cursor, end) < INT_BYTES) {
            return ParseResult.malformedResult();
        }
        int streamNameLength = readInt(bytes, cursor);
        cursor += INT_BYTES;
        if (streamNameLength <= 0 || streamNameLength > remaining(cursor, end)) {
            return ParseResult.malformedResult();
        }

        String streamName = new String(bytes, cursor, streamNameLength, StandardCharsets.UTF_8);
        if (streamName.isBlank()) {
            return ParseResult.malformedResult();
        }
        cursor += streamNameLength;
        if (remaining(cursor, end) < INT_BYTES) {
            return ParseResult.malformedResult();
        }
        int payloadLength = readInt(bytes, cursor);
        cursor += INT_BYTES;
        if (payloadLength < 0 || payloadLength > remaining(cursor, end)) {
            return ParseResult.malformedResult();
        }
        int payloadOffset = cursor;
        cursor += payloadLength;
        if (cursor != end) {
            return ParseResult.malformedResult();
        }
        return ParseResult.routedResult(streamName, payloadOffset, payloadLength);
    }

    private static int remaining(int cursor, int end) {
        return Math.max(0, end - cursor);
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
            | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    public record ParseResult(
        boolean headerPresent,
        boolean malformed,
        String streamName,
        int payloadOffset,
        int payloadLength
    ) {
        static ParseResult legacyResult() {
            return new ParseResult(false, false, null, -1, -1);
        }

        static ParseResult malformedResult() {
            return new ParseResult(true, true, null, -1, -1);
        }

        static ParseResult routedResult(String streamName, int payloadOffset, int payloadLength) {
            return new ParseResult(true, false, streamName, payloadOffset, payloadLength);
        }
    }
}
