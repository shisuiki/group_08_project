package edu.illinois.group8.storage.db;

import com.fasterxml.jackson.databind.JsonNode;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.SerializedCanonicalEvent;
import edu.illinois.group8.canonical.TickerUpdate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalDbEventMapperTest {
    @Test
    void mapsCanonicalEventFieldsAndPayloadJson() throws Exception {
        CanonicalDbEventMapper mapper = new CanonicalDbEventMapper();
        TickerUpdate event = tickerEvent();

        CanonicalDbEvent dbEvent = mapper.toDbEvent(event);

        assertEquals("event-1", dbEvent.eventId());
        assertEquals("raw-1", dbEvent.rawEventId());
        assertEquals("replay-1", dbEvent.replayId());
        assertEquals(EventType.TICKER_UPDATE.streamName(), dbEvent.streamName());
        assertEquals(EventType.TICKER_UPDATE.eventType(), dbEvent.eventType());
        assertEquals(EventType.TICKER_UPDATE.schemaVersion(), dbEvent.schemaVersion());
        assertEquals("MARKET-1", dbEvent.marketTicker());
        assertEquals(1_700_000_000_000L, dbEvent.eventTsMs());
        assertEquals(123L, dbEvent.ingestTsNs());
        assertEquals(456L, dbEvent.publishTsNs());

        JsonNode payload = new JsonCanonicalSerializer().mapper().readTree(dbEvent.payload());
        assertEquals("event-1", payload.path("event_id").asText());
        assertEquals(EventType.TICKER_UPDATE.eventType(), payload.path("event_type").asText());
        assertEquals(EventType.TICKER_UPDATE.schemaVersion(), payload.path("schema_version").asInt());
        assertEquals(EventType.TICKER_UPDATE.streamName(), payload.path("stream_name").asText());
        assertEquals(480_000L, payload.path("price_micros").asLong());
        assertEquals("MARKET-1", payload.path("metadata").path("market_ticker").asText());
        assertEquals("raw-1", payload.path("metadata").path("raw_event_id").asText());
        assertEquals("replay-1", payload.path("metadata").path("replay_id").asText());
        assertEquals(1_700_000_000_000L, payload.path("metadata").path("event_ts_ms").asLong());
        assertEquals(123L, payload.path("metadata").path("ingest_ts_ns").asLong());
        assertEquals(456L, payload.path("metadata").path("publish_ts_ns").asLong());
    }

    @Test
    void mapsSerializedCanonicalEventPayloadWithoutSerializer() {
        CanonicalDbEventMapper mapper = new CanonicalDbEventMapper(new ThrowingJsonCanonicalSerializer());
        TickerUpdate event = tickerEvent();
        String payload = "{\"event_id\":\"event-1\",\"stream_name\":\"canonical.ticker\",\"sentinel\":true}";
        SerializedCanonicalEvent serializedEvent = new SerializedCanonicalEvent(
            event,
            payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        CanonicalDbEvent dbEvent = mapper.toDbEvent(serializedEvent);

        assertEquals("event-1", dbEvent.eventId());
        assertEquals(EventType.TICKER_UPDATE.streamName(), dbEvent.streamName());
        assertEquals(payload, dbEvent.payload());
    }

    @Test
    void nullEventThrows() {
        CanonicalDbEventMapper mapper = new CanonicalDbEventMapper();

        assertThrows(NullPointerException.class, () -> mapper.toDbEvent((TickerUpdate) null));
    }

    private static TickerUpdate tickerEvent() {
        return new TickerUpdate(
            "event-1",
            new EventMetadata(
                "kalshi",
                "ticker",
                11L,
                22L,
                "MARKET-1",
                "market-id-1",
                1_700_000_000_000L,
                123L,
                456L,
                "raw-1",
                "replay-1"
            ),
            480_000L,
            450_000L,
            530_000L,
            null,
            null,
            null,
            null,
            null
        );
    }

    private static final class ThrowingJsonCanonicalSerializer extends JsonCanonicalSerializer {
        @Override
        public String toJson(edu.illinois.group8.canonical.CanonicalEvent event) {
            throw new IllegalStateException("serialized path must not call toJson");
        }
    }
}
