package edu.illinois.group8.storage.db;

import edu.illinois.group8.parser.KalshiCanonicalParser;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RawWsDbEventMapperTest {
    private static final String TICKER_PAYLOAD =
        "{\"type\":\"ticker\",\"sid\":11,\"seq\":7,\"msg\":{\"market_ticker\":\"FED-23DEC-T3.00\",\"market_id\":\"m1\",\"price_dollars\":\"0.480\",\"ts_ms\":1669149841000}}";
    private static final String TICKER_FALLBACK_PAYLOAD =
        "{\"type\":\"ticker\",\"seq\":\"8\",\"msg\":{\"ticker\":\"FALLBACK-TICKER\"}}";
    private static final String MALFORMED_PAYLOAD = "{bad";

    @Test
    void mapsMarketPayloadFieldsAndStableIds() {
        RawWsDbEventMapper mapper = new RawWsDbEventMapper();
        RawWsDbEventInput input = input(TICKER_PAYLOAD);

        RawWsDbEvent event = mapper.toDbEvent(input);

        assertEquals(KalshiCanonicalParser.rawEventId(TICKER_PAYLOAD), event.rawEventId());
        assertEquals("raw_7209534ab232a48af35a61a5", event.rawEventId());
        assertEquals(
            "7209534ab232a48af35a61a59a1b5f7cb97e8d4b1229b6ad3e628bdf3ba855d4",
            event.payloadSha256()
        );
        assertEquals("raw_" + event.payloadSha256().substring(0, 24), event.rawEventId());
        assertEquals("kalshi-ws", event.source());
        assertEquals("capture-1", event.captureId());
        assertEquals("connection-1", event.connectionId());
        assertEquals(42L, event.connectionSequence());
        assertEquals(123_456_789L, event.receiveTsNs());
        assertEquals(Instant.parse("2026-05-19T00:00:00Z"), event.receiveWallTs());
        assertEquals("FED-23DEC-T3.00", event.marketTicker());
        assertEquals("ticker", event.sourceChannel());
        assertEquals(7L, event.sourceSequence());
        assertEquals(TICKER_PAYLOAD, event.rawPayload());
        assertEquals("stored", event.ingestStatus());
    }

    @Test
    void mapsTickerFallbackAndStringSequence() {
        RawWsDbEventMapper mapper = new RawWsDbEventMapper();

        RawWsDbEvent event = mapper.toDbEvent(input(TICKER_FALLBACK_PAYLOAD));

        assertEquals("FALLBACK-TICKER", event.marketTicker());
        assertEquals("ticker", event.sourceChannel());
        assertEquals(8L, event.sourceSequence());
    }

    @Test
    void malformedPayloadStillStoresRawAuditFields() {
        RawWsDbEventMapper mapper = new RawWsDbEventMapper();
        RawWsDbEventInput input = input(MALFORMED_PAYLOAD);

        RawWsDbEvent event = mapper.toDbEvent(input);

        assertEquals(KalshiCanonicalParser.rawEventId(MALFORMED_PAYLOAD), event.rawEventId());
        assertEquals(
            "17eed55d881c0dd5fe0935a98d89dd9e52d62aed941f3fb7d17bd490e5aa3e8f",
            event.payloadSha256()
        );
        assertEquals("raw_" + event.payloadSha256().substring(0, 24), event.rawEventId());
        assertEquals("kalshi-ws", event.source());
        assertEquals("capture-1", event.captureId());
        assertEquals("connection-1", event.connectionId());
        assertEquals(MALFORMED_PAYLOAD, event.rawPayload());
        assertNull(event.marketTicker());
        assertNull(event.sourceChannel());
        assertNull(event.sourceSequence());
    }

    @Test
    void nullRawPayloadThrows() {
        RawWsDbEventMapper mapper = new RawWsDbEventMapper();
        RawWsDbEventInput input = new RawWsDbEventInput(
            "kalshi-ws",
            "capture-1",
            "connection-1",
            42L,
            123_456_789L,
            Instant.parse("2026-05-19T00:00:00Z"),
            null,
            "stored"
        );

        assertThrows(NullPointerException.class, () -> mapper.toDbEvent(input));
    }

    @Test
    void nullReceiveWallTsThrows() {
        RawWsDbEventMapper mapper = new RawWsDbEventMapper();
        RawWsDbEventInput input = new RawWsDbEventInput(
            "kalshi-ws",
            "capture-1",
            "connection-1",
            42L,
            123_456_789L,
            null,
            TICKER_PAYLOAD,
            "stored"
        );

        assertThrows(NullPointerException.class, () -> mapper.toDbEvent(input));
    }

    private static RawWsDbEventInput input(String rawPayload) {
        return new RawWsDbEventInput(
            "kalshi-ws",
            "capture-1",
            "connection-1",
            42L,
            123_456_789L,
            Instant.parse("2026-05-19T00:00:00Z"),
            rawPayload,
            "stored"
        );
    }
}
