package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.FeatureOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendFeatureStoreTest {

    @Test
    void acceptsAcrossMultipleMarketsAndExposesSortedSymbols() {
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(bbo("MKT-B", 100L, 5_000_000L));
        store.accept(bbo("MKT-A", 100L, 5_000_000L));
        store.accept(bbo("MKT-C", 100L, 5_000_000L));

        Set<String> symbols = store.symbols();
        assertEquals(List.of("MKT-A", "MKT-B", "MKT-C"), List.copyOf(symbols));
        assertEquals(3, store.symbolCount());
    }

    @Test
    void snapshotReturnsOrderedCopyByEventTs() {
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(bbo("MKT-1", 300L, 5_500_000L));
        store.accept(bbo("MKT-1", 100L, 5_000_000L));
        store.accept(bbo("MKT-1", 200L, 5_250_000L));

        List<FeatureOutput> snapshot = store.snapshot("MKT-1", FrontendFeatureStore.BBO_FEATURE);
        assertEquals(3, snapshot.size());
        assertEquals(100L, snapshot.get(0).eventTsMs());
        assertEquals(200L, snapshot.get(1).eventTsMs());
        assertEquals(300L, snapshot.get(2).eventTsMs());
    }

    @Test
    void evictionAtCapDropsOldestEntries() {
        FrontendFeatureStore store = new FrontendFeatureStore(3, 100);
        for (long ts = 100L; ts <= 105L; ts++) {
            store.accept(bbo("MKT-1", ts, 5_000_000L + ts));
        }
        List<FeatureOutput> snapshot = store.snapshot("MKT-1", FrontendFeatureStore.BBO_FEATURE);
        assertEquals(3, snapshot.size());
        assertEquals(103L, snapshot.get(0).eventTsMs());
        assertEquals(105L, snapshot.get(2).eventTsMs());
    }

    @Test
    void latestReturnsMostRecentPerFeature() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        store.accept(bbo("MKT-1", 100L, 5_000_000L));
        store.accept(bbo("MKT-1", 200L, 5_250_000L));
        store.accept(trade("MKT-1", 150L, "T-1"));

        Optional<FeatureOutput> latestBbo = store.latest("MKT-1", FrontendFeatureStore.BBO_FEATURE);
        Optional<FeatureOutput> latestTrade = store.latest("MKT-1", "feature.trade_tape");
        Optional<FeatureOutput> latestMissing = store.latest("MKT-1", "feature.ticker_snapshot");

        assertTrue(latestBbo.isPresent());
        assertEquals(200L, latestBbo.get().eventTsMs());
        assertTrue(latestTrade.isPresent());
        assertEquals("T-1", latestTrade.get().values().get("trade_id"));
        assertFalse(latestMissing.isPresent());
    }

    @Test
    void blankMarketTickerIsIgnored() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        store.accept(bbo("", 100L, 5_000_000L));
        store.accept(bbo(null, 100L, 5_000_000L));
        assertTrue(store.symbols().isEmpty());
        assertEquals(0L, store.totalAccepted());
    }

    static FeatureOutput bbo(String market, long ts, long midpoint) {
        return new FeatureOutput(
            FrontendFeatureStore.BBO_FEATURE,
            FrontendFeatureStore.BBO_FEATURE,
            market,
            ts,
            "evt-" + ts,
            Map.of(
                "bid_price_micros", midpoint - 100_000L,
                "ask_price_micros", midpoint + 100_000L,
                "midpoint_micros", midpoint
            )
        );
    }

    static FeatureOutput trade(String market, long ts, String tradeId) {
        return new FeatureOutput(
            "feature.trade_tape",
            "feature.trade_tape",
            market,
            ts,
            "evt-t-" + ts,
            Map.of("trade_id", tradeId)
        );
    }
}
