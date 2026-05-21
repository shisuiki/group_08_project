package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalEnvelopeTest {
    private static final ObjectMapper MAPPER = new JsonCanonicalSerializer().mapper();
    private static final String PAYLOAD_WITH_STREAM = """
        {"event_id":"e1","event_type":"market_trade","schema_version":1,"stream_name":"canonical.trade","metadata":{"source":"kalshi","market_ticker":"M","event_ts_ms":1700000000000},"recorder_metadata":{"consumer_receive_ts_ns":123456789}}
        """.trim();
    private static final String PAYLOAD_WITHOUT_STREAM = """
        {"event_id":"e2","event_type":"market_trade","schema_version":1,"metadata":{"source":"kalshi","market_ticker":"M","event_ts_ms":1700000000001}}
        """.trim();

    @Test
    void bytePayloadParsingMatchesStringParsingAndRetainsPayload() {
        byte[] bytes = PAYLOAD_WITH_STREAM.getBytes(StandardCharsets.UTF_8);
        CanonicalEnvelope fromString = CanonicalEnvelope.fromPayload("fallback.stream", PAYLOAD_WITH_STREAM, MAPPER);
        CanonicalEnvelope fromBytes = CanonicalEnvelope.fromPayloadBytes(
            "fallback.stream",
            bytes,
            0,
            bytes.length,
            MAPPER
        );

        assertEquals(fromString.streamName(), fromBytes.streamName());
        assertEquals("canonical.trade", fromBytes.streamName());
        assertEquals(PAYLOAD_WITH_STREAM, fromBytes.payload());
        assertEquals(fromString.event(), fromBytes.event());
        assertEquals(Long.valueOf(1700000000000L), fromBytes.eventTsMs());
        assertEquals(Long.valueOf(123456789L), fromBytes.consumerReceiveTsNs());
    }

    @Test
    void bytePayloadParsingFallsBackToProvidedStreamName() {
        byte[] bytes = PAYLOAD_WITHOUT_STREAM.getBytes(StandardCharsets.UTF_8);

        CanonicalEnvelope envelope = CanonicalEnvelope.fromPayloadBytes(
            "fallback.stream",
            bytes,
            0,
            bytes.length,
            MAPPER
        );

        assertEquals("fallback.stream", envelope.streamName());
        assertEquals(PAYLOAD_WITHOUT_STREAM, envelope.payload());
        assertEquals(Long.valueOf(1700000000001L), envelope.eventTsMs());
        assertNull(envelope.consumerReceiveTsNs());
    }

    @Test
    void bytePayloadParsingHonorsOffsetAndLength() {
        byte[] payload = PAYLOAD_WITH_STREAM.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[payload.length + 9];
        Arrays.fill(padded, (byte) '#');
        int offset = 4;
        System.arraycopy(payload, 0, padded, offset, payload.length);

        CanonicalEnvelope envelope = CanonicalEnvelope.fromPayloadBytes(
            "fallback.stream",
            padded,
            offset,
            payload.length,
            MAPPER
        );

        assertEquals("canonical.trade", envelope.streamName());
        assertEquals(PAYLOAD_WITH_STREAM, envelope.payload());
        assertEquals("e1", envelope.eventId());
    }

    @Test
    void bytePayloadParsingCanOverrideLiveConsumerReceiveTimestamp() {
        byte[] bytes = PAYLOAD_WITH_STREAM.getBytes(StandardCharsets.UTF_8);

        CanonicalEnvelope envelope = CanonicalEnvelope.fromPayloadBytes(
            "fallback.stream",
            bytes,
            0,
            bytes.length,
            987654321L,
            MAPPER
        );

        assertEquals(Long.valueOf(987654321L), envelope.consumerReceiveTsNs());
        assertEquals(PAYLOAD_WITH_STREAM, envelope.payload());
    }

    @Test
    void malformedBytePayloadThrowsIllegalArgumentException() {
        byte[] bytes = "{\"event_id\":".getBytes(StandardCharsets.UTF_8);

        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> CanonicalEnvelope.fromPayloadBytes("fallback.stream", bytes, 0, bytes.length, MAPPER)
        );
        assertTrue(thrown.getMessage().contains("fallback.stream"));
    }
}
