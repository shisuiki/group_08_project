package edu.illinois.group8.backfill;

import edu.illinois.group8.storage.db.MarketMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketMetadataMapperTest {
    private final MarketMetadataMapper mapper = new MarketMetadataMapper();

    @Test
    void mapsRealisticMarketsPayloadToMetadata() {
        String payload = """
            {"markets":[{
              "ticker":"M",
              "event_ticker":"EVENT",
              "series_ticker":"SERIES",
              "status":"open",
              "open_time":"2026-05-19T01:00:00Z",
              "close_time":"2026-05-20T02:00:00Z",
              "settlement_time":"2026-05-21T03:00:00Z",
              "rules":{"settlement":"official"},
              "yes_bid":55
            }],"cursor":""}
            """;

        List<MarketMetadata> metadata = mapper.fromMarketsResponse(payload);

        assertEquals(1, metadata.size());
        MarketMetadata market = metadata.get(0);
        assertEquals("M", market.marketTicker());
        assertEquals("EVENT", market.eventTicker());
        assertEquals("SERIES", market.seriesTicker());
        assertEquals("open", market.status());
        assertEquals(Instant.parse("2026-05-19T01:00:00Z"), market.openTime());
        assertEquals(Instant.parse("2026-05-20T02:00:00Z"), market.closeTime());
        assertEquals(Instant.parse("2026-05-21T03:00:00Z"), market.settlementTime());
        assertEquals("{\"settlement\":\"official\"}", market.rulesPayload());
        assertTrue(market.marketPayload().contains("\"ticker\":\"M\""));
        assertTrue(market.marketPayload().contains("\"yes_bid\":55"));
    }

    @Test
    void fallsBackToMarketTickerAndRulebookPayload() {
        String payload = """
            {"markets":[{
              "market_ticker":"M-FALLBACK",
              "rulebook":[{"term":"a"}],
              "status":"closed"
            }]}
            """;

        List<MarketMetadata> metadata = mapper.fromMarketsResponse(payload);

        assertEquals(1, metadata.size());
        assertEquals("M-FALLBACK", metadata.get(0).marketTicker());
        assertEquals("closed", metadata.get(0).status());
        assertEquals("[{\"term\":\"a\"}]", metadata.get(0).rulesPayload());
    }

    @Test
    void skipsBlankMissingAndNonObjectMarketEntries() {
        String payload = """
            {"markets":[
              {"ticker":" "},
              {"status":"open"},
              "not-an-object",
              {"ticker":"M"}
            ]}
            """;

        List<MarketMetadata> metadata = mapper.fromMarketsResponse(payload);

        assertEquals(1, metadata.size());
        assertEquals("M", metadata.get(0).marketTicker());
    }

    @Test
    void missingOrNonArrayMarketsReturnsEmpty() {
        assertEquals(List.of(), mapper.fromMarketsResponse("{}"));
        assertEquals(List.of(), mapper.fromMarketsResponse("{\"markets\":{}}"));
    }

    @Test
    void absentBlankAndScalarRulesFieldsBecomeNull() {
        String payload = """
            {"markets":[{"ticker":"M","open_time":"","rules":"text rules"}]}
            """;

        MarketMetadata metadata = mapper.fromMarketsResponse(payload).get(0);

        assertNull(metadata.openTime());
        assertNull(metadata.rulesPayload());
    }

    @Test
    void invalidWholeJsonFailsClearly() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> mapper.fromMarketsResponse("{bad")
        );

        assertTrue(thrown.getMessage().contains("Invalid markets response JSON"));
    }

    @Test
    void invalidTimestampFailsClearly() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> mapper.fromMarketsResponse("{\"markets\":[{\"ticker\":\"M\",\"open_time\":\"not-time\"}]}")
        );

        assertTrue(thrown.getMessage().contains("Invalid open_time timestamp for market M"));
    }
}
