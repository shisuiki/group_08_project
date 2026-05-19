package edu.illinois.group8.parser;

import edu.illinois.group8.canonical.FixedPoint;
import edu.illinois.group8.canonical.MarketTrade;
import edu.illinois.group8.canonical.OrderBookDeltaEvent;
import edu.illinois.group8.canonical.OrderBookSnapshotEvent;
import edu.illinois.group8.canonical.ParserErrorEvent;
import edu.illinois.group8.canonical.TickerUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class KalshiCanonicalParserTest {
    private final KalshiCanonicalParser parser = new KalshiCanonicalParser();

    @Test
    void parsesCurrentTradeSchema() {
        CanonicalParseResult result = parser.parseWebSocketMessage("""
            {"type":"trade","sid":11,"msg":{"trade_id":"abc","market_ticker":"HIGHNY-22DEC23-B53.5","market_id":"m1","yes_price_dollars":"0.360","no_price_dollars":"0.640","count_fp":"136.00","taker_side":"no","ts":1669149841,"ts_ms":1669149841000}}
            """);

        MarketTrade trade = assertInstanceOf(MarketTrade.class, result.canonicalEvents().get(0));
        assertEquals("canonical.trade", trade.streamName());
        assertEquals("HIGHNY-22DEC23-B53.5", trade.metadata().marketTicker());
        assertEquals(360_000L, trade.yesPriceMicros());
        assertEquals(136_000_000L, trade.quantityMicros());
    }

    @Test
    void parsesTickerAndOpenInterest() {
        CanonicalParseResult result = parser.parseWebSocketMessage("""
            {"type":"ticker","sid":11,"msg":{"market_ticker":"FED-23DEC-T3.00","price_dollars":"0.480","yes_bid_dollars":"0.450","yes_ask_dollars":"0.530","volume_fp":"33896.00","open_interest_fp":"20422.00","dollar_volume":16948,"dollar_open_interest":10211,"yes_bid_size_fp":"300.00","yes_ask_size_fp":"150.00","last_trade_size_fp":"25.00","ts_ms":1669149841000}}
            """);

        assertEquals(2, result.canonicalEvents().size());
        TickerUpdate ticker = assertInstanceOf(TickerUpdate.class, result.canonicalEvents().get(0));
        assertEquals(480_000L, ticker.priceMicros());
        assertEquals(300_000_000L, ticker.yesBidQuantityMicros());
    }

    @Test
    void parsesOrderBookSnapshotAndDelta() {
        CanonicalParseResult snapshotResult = parser.parseWebSocketMessage("""
            {"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"FED-23DEC-T3.00","market_id":"m1","yes_dollars_fp":[["0.0800","300.00"],["0.2200","333.00"]],"no_dollars_fp":[["0.5400","20.00"]]}}
            """);
        OrderBookSnapshotEvent snapshot = assertInstanceOf(OrderBookSnapshotEvent.class, snapshotResult.canonicalEvents().get(0));
        assertEquals(2, snapshot.yesBids().size());
        assertEquals(FixedPoint.priceDollarsToMicros("0.2200"), snapshot.yesBids().get(1).priceMicros());

        CanonicalParseResult deltaResult = parser.parseWebSocketMessage("""
            {"type":"orderbook_delta","sid":2,"seq":3,"msg":{"market_ticker":"FED-23DEC-T3.00","market_id":"m1","price_dollars":"0.960","delta_fp":"-54.00","side":"yes","ts_ms":1669149841000}}
            """);
        OrderBookDeltaEvent delta = assertInstanceOf(OrderBookDeltaEvent.class, deltaResult.canonicalEvents().get(0));
        assertEquals("yes", delta.side());
        assertEquals(-54_000_000L, delta.deltaQuantityMicros());
    }

    @Test
    void malformedJsonProducesParserError() {
        CanonicalParseResult result = parser.parseWebSocketMessage("{bad");

        assertEquals(1, result.canonicalEvents().size());
        ParserErrorEvent error = assertInstanceOf(ParserErrorEvent.class, result.canonicalEvents().get(0));
        assertEquals("malformed_json", error.errorCode());
    }

    @Test
    void eventIdSanitizesPartsWithoutRegex() {
        assertEquals("raw_test_a_b_c_d", KalshiCanonicalParser.eventId("raw_test", "a.b/c d"));
        assertEquals("raw_test_a_b_c_d_e-f", KalshiCanonicalParser.eventId("raw_test", "a.b/c d_e-f"));
    }
}
