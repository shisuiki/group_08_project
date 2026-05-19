package edu.illinois.group8.publication;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalRouteEnvelopeTest {
    @Test
    void wrapProducesNonJsonEnvelopeAndParsesPayloadSlice() {
        byte[] payload = bytes("{\"event_id\":\"e1\",\"stream_name\":\"canonical.trade\"}");
        byte[] envelope = CanonicalRouteEnvelope.wrap("canonical.trade", payload);

        assertEquals('C', envelope[0]);
        assertEquals('R', envelope[1]);
        assertEquals('H', envelope[2]);
        assertEquals('1', envelope[3]);
        assertFalse(envelope[0] == '{');

        CanonicalRouteEnvelope.ParseResult route =
            CanonicalRouteEnvelope.parse(envelope, 0, envelope.length);

        assertTrue(route.headerPresent());
        assertFalse(route.malformed());
        assertEquals("canonical.trade", route.streamName());
        assertArrayEquals(
            payload,
            Arrays.copyOfRange(envelope, route.payloadOffset(), route.payloadOffset() + route.payloadLength())
        );
    }

    @Test
    void headerRouteDoesNotDependOnPayloadStreamName() {
        byte[] payload = bytes("{\"event_id\":\"e1\",\"stream_name\":\"derived.top_of_book\"}");
        byte[] envelope = CanonicalRouteEnvelope.wrap("canonical.trade", payload);

        CanonicalRouteEnvelope.ParseResult route =
            CanonicalRouteEnvelope.parse(envelope, 0, envelope.length);

        assertEquals("canonical.trade", route.streamName());
        assertArrayEquals(
            payload,
            Arrays.copyOfRange(envelope, route.payloadOffset(), route.payloadOffset() + route.payloadLength())
        );
    }

    @Test
    void parseSupportsNonZeroOffsetSlice() {
        byte[] payload = bytes("{\"event_id\":\"e1\"}");
        byte[] envelope = CanonicalRouteEnvelope.wrap("system.sequence_gaps", payload);
        byte[] data = new byte[envelope.length + 5];
        System.arraycopy(bytes("xx"), 0, data, 0, 2);
        System.arraycopy(envelope, 0, data, 2, envelope.length);
        System.arraycopy(bytes("yyy"), 0, data, 2 + envelope.length, 3);

        CanonicalRouteEnvelope.ParseResult route =
            CanonicalRouteEnvelope.parse(data, 2, envelope.length);

        assertTrue(route.headerPresent());
        assertFalse(route.malformed());
        assertEquals("system.sequence_gaps", route.streamName());
        assertArrayEquals(
            payload,
            Arrays.copyOfRange(data, route.payloadOffset(), route.payloadOffset() + route.payloadLength())
        );
    }

    @Test
    void legacyJsonPayloadDoesNotClaimHeader() {
        byte[] payload = bytes("{\"event_id\":\"e1\",\"stream_name\":\"canonical.trade\"}");

        CanonicalRouteEnvelope.ParseResult route =
            CanonicalRouteEnvelope.parse(payload, 0, payload.length);

        assertFalse(route.headerPresent());
        assertFalse(route.malformed());
    }

    @Test
    void matchingMagicWithMalformedHeaderDoesNotFallbackToLegacy() {
        byte[] envelope = CanonicalRouteEnvelope.wrap("canonical.trade", bytes("{\"event_id\":\"e1\"}"));

        CanonicalRouteEnvelope.ParseResult truncated =
            CanonicalRouteEnvelope.parse(envelope, 0, envelope.length - 1);
        assertTrue(truncated.headerPresent());
        assertTrue(truncated.malformed());

        byte[] trailing = Arrays.copyOf(envelope, envelope.length + 1);
        CanonicalRouteEnvelope.ParseResult withTrailing =
            CanonicalRouteEnvelope.parse(trailing, 0, trailing.length);
        assertTrue(withTrailing.headerPresent());
        assertTrue(withTrailing.malformed());
    }

    @Test
    void rejectsBlankStreamNamesBeforeWrapping() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CanonicalRouteEnvelope.wrap(" ", bytes("{\"event_id\":\"e1\"}"))
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
