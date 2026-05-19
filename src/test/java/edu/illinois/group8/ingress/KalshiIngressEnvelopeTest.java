package edu.illinois.group8.ingress;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KalshiIngressEnvelopeTest {
    private static final String RAW_PAYLOAD = "{\"type\":\"ticker\",\"msg\":{\"market_ticker\":\"M\"}}";
    private static final Instant RECEIVE_WALL_TS = Instant.parse("2026-05-19T00:00:00Z");

    @Test
    void wrapBytesProducesBinaryEnvelopeAndParsesWithoutJsonEnvelope() {
        byte[] bytes = KalshiIngressEnvelope.wrapBytes(
            RAW_PAYLOAD,
            123L,
            RECEIVE_WALL_TS,
            "conn-1",
            "replay-1"
        );

        assertEquals('K', bytes[0]);
        assertEquals('I', bytes[1]);
        assertEquals('E', bytes[2]);
        assertEquals('1', bytes[3]);
        assertNotEquals('{', bytes[0]);

        KalshiIngressEnvelope envelope = KalshiIngressEnvelope.parse(bytes, -1L);

        assertTrue(envelope.enveloped());
        assertEquals(RAW_PAYLOAD, envelope.rawPayload());
        assertEquals(123L, envelope.receiveTsNs());
        assertEquals(RECEIVE_WALL_TS.toString(), envelope.receiveWallTs());
        assertEquals("conn-1", envelope.connectionId());
        assertEquals("replay-1", envelope.replayId());
    }

    @Test
    void binaryEnvelopeSupportsNullOptionalFieldsAndSliceParsing() {
        byte[] bytes = KalshiIngressEnvelope.wrapBytes(RAW_PAYLOAD, 456L, null, " ", null);
        byte[] padded = new byte[bytes.length + 7];
        Arrays.fill(padded, (byte) '#');
        System.arraycopy(bytes, 0, padded, 3, bytes.length);

        KalshiIngressEnvelope envelope = KalshiIngressEnvelope.parse(padded, 3, bytes.length, -1L);

        assertTrue(envelope.enveloped());
        assertEquals(RAW_PAYLOAD, envelope.rawPayload());
        assertEquals(456L, envelope.receiveTsNs());
        assertNull(envelope.receiveWallTs());
        assertNull(envelope.connectionId());
        assertNull(envelope.replayId());
    }

    @Test
    void legacyJsonEnvelopeStringAndBytesStillParse() {
        String jsonEnvelope = KalshiIngressEnvelope.wrap(
            RAW_PAYLOAD,
            789L,
            RECEIVE_WALL_TS,
            "conn-json",
            "replay-json"
        );

        KalshiIngressEnvelope fromString = KalshiIngressEnvelope.parse(jsonEnvelope, -1L);
        KalshiIngressEnvelope fromBytes = KalshiIngressEnvelope.parse(
            jsonEnvelope.getBytes(StandardCharsets.UTF_8),
            -1L
        );

        assertTrue(fromString.enveloped());
        assertTrue(fromBytes.enveloped());
        assertEquals(RAW_PAYLOAD, fromString.rawPayload());
        assertEquals(RAW_PAYLOAD, fromBytes.rawPayload());
        assertEquals(789L, fromString.receiveTsNs());
        assertEquals(789L, fromBytes.receiveTsNs());
        assertEquals("conn-json", fromString.connectionId());
        assertEquals("conn-json", fromBytes.connectionId());
        assertEquals("replay-json", fromString.replayId());
        assertEquals("replay-json", fromBytes.replayId());
    }

    @Test
    void nakedPayloadFallbackStillParsesAsRawPayload() {
        KalshiIngressEnvelope fromString = KalshiIngressEnvelope.parse(RAW_PAYLOAD, 111L);
        KalshiIngressEnvelope fromBytes = KalshiIngressEnvelope.parse(
            RAW_PAYLOAD.getBytes(StandardCharsets.UTF_8),
            222L
        );

        assertFalse(fromString.enveloped());
        assertFalse(fromBytes.enveloped());
        assertEquals(RAW_PAYLOAD, fromString.rawPayload());
        assertEquals(RAW_PAYLOAD, fromBytes.rawPayload());
        assertEquals(111L, fromString.receiveTsNs());
        assertEquals(222L, fromBytes.receiveTsNs());
    }

    @Test
    void malformedBinaryEnvelopeFallsBackWithoutThrowing() {
        byte[] valid = KalshiIngressEnvelope.wrapBytes(RAW_PAYLOAD, 123L, RECEIVE_WALL_TS, "conn-1", null);
        byte[] truncated = Arrays.copyOf(valid, 18);
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);

        KalshiIngressEnvelope truncatedEnvelope = assertDoesNotThrow(
            () -> KalshiIngressEnvelope.parse(truncated, 999L)
        );
        KalshiIngressEnvelope trailingEnvelope = assertDoesNotThrow(
            () -> KalshiIngressEnvelope.parse(trailing, 999L)
        );

        assertFalse(truncatedEnvelope.enveloped());
        assertFalse(trailingEnvelope.enveloped());
        assertEquals(999L, truncatedEnvelope.receiveTsNs());
        assertEquals(999L, trailingEnvelope.receiveTsNs());
        assertEquals(new String(truncated, StandardCharsets.UTF_8), truncatedEnvelope.rawPayload());
        assertEquals(new String(trailing, StandardCharsets.UTF_8), trailingEnvelope.rawPayload());
    }
}
