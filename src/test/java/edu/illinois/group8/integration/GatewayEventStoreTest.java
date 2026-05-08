package edu.illinois.group8.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GatewayEventStoreTest {
    @Test
    void indexesSymbolsQuotesAndMinuteBars() {
        GatewayEventStore store = new GatewayEventStore(100);
        store.recordJson("""
            {"event_id":"e1","event_type":"market_trade","schema_version":1,"stream_name":"canonical.trade","metadata":{"market_ticker":"M","market_id":"m1","event_ts_ms":1000},"trade_id":"t1","yes_price_micros":250000,"no_price_micros":750000,"quantity_micros":2000000,"taker_side":"yes"}
            """, "journal");
        store.recordJson("""
            {"event_id":"e2","event_type":"ticker_update","schema_version":1,"stream_name":"canonical.ticker","metadata":{"market_ticker":"M","market_id":"m1","event_ts_ms":30000},"price_micros":300000,"yes_bid_micros":290000,"yes_ask_micros":310000,"yes_bid_quantity_micros":1000000,"yes_ask_quantity_micros":2000000,"volume_micros":3000000}
            """, "journal");
        store.recordJson("""
            {"event_id":"e3","event_type":"market_trade","schema_version":1,"stream_name":"canonical.trade","metadata":{"market_ticker":"M","market_id":"m1","event_ts_ms":61000},"trade_id":"t2","yes_price_micros":400000,"no_price_micros":600000,"quantity_micros":1000000,"taker_side":"no"}
            """, "journal");

        assertEquals(1, store.symbols("").size());
        Map<String, Object> history = store.history("M", "1", null, null, 10);
        List<Map<String, Object>> bars = (List<Map<String, Object>>) history.get("bars");

        assertEquals(2, bars.size());
        assertEquals(0.25, bars.get(0).get("open"));
        assertEquals(0.30, bars.get(0).get("close"));
        assertEquals(0.40, bars.get(1).get("close"));

        List<Map<String, Object>> quotes = (List<Map<String, Object>>) store.quotes(List.of("M")).get("quotes");
        assertFalse(quotes.isEmpty());
        assertEquals(0.29, quotes.get(0).get("bid"));
        assertEquals(0.31, quotes.get(0).get("ask"));
    }
}
