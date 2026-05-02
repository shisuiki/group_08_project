package edu.illinois.group8.parser;

import edu.illinois.group8.canonical.MarketTrade;
import edu.illinois.group8.canonical.OrderBookSnapshotEvent;
import edu.illinois.group8.canonical.TickerUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class KalshiRestParserTest {
    private final KalshiRestParser parser = new KalshiRestParser();

    @Test
    void parsesHistoricalTradesResponse() {
        CanonicalParseResult result = parser.parseTradesResponse("""
            {"trades":[{"trade_id":"t1","ticker":"M","count_fp":"10.00","yes_price_dollars":"0.5600","no_price_dollars":"0.4400","taker_side":"yes","created_time":"2023-11-07T05:31:56Z"}],"cursor":""}
            """, 42L);

        MarketTrade trade = assertInstanceOf(MarketTrade.class, result.canonicalEvents().get(0));
        assertEquals("M", trade.metadata().marketTicker());
        assertEquals(560_000L, trade.yesPriceMicros());
    }

    @Test
    void parsesRestOrderbookResponse() {
        CanonicalParseResult result = parser.parseMarketOrderbookResponse("M", """
            {"orderbook_fp":{"yes_dollars":[["0.1500","100.00"]],"no_dollars":[["0.2500","20.00"]]}}
            """, 42L);

        OrderBookSnapshotEvent snapshot = assertInstanceOf(OrderBookSnapshotEvent.class, result.canonicalEvents().get(0));
        assertEquals(150_000L, snapshot.yesBids().get(0).priceMicros());
        assertEquals(20_000_000L, snapshot.noBids().get(0).quantityMicros());
    }

    @Test
    void parsesCandlesticksAsTickerUpdates() {
        CanonicalParseResult result = parser.parseCandlesticksResponse("""
            {"ticker":"M","candlesticks":[{"end_period_ts":1700000000,"yes_bid":{"close_dollars":"0.5600"},"yes_ask":{"close_dollars":"0.5800"},"volume_fp":"10.00","open_interest_fp":"20.00"}]}
            """, 42L);

        TickerUpdate ticker = assertInstanceOf(TickerUpdate.class, result.canonicalEvents().get(0));
        assertEquals(560_000L, ticker.yesBidMicros());
        assertEquals(2, result.canonicalEvents().size());
    }
}
