package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.FeatureOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarSynthesisTest {

    @Test
    void buildsOhlcPerBucketFromMidpoints() {
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(bbo("MKT-1", 1_000L, 500_000L));
        store.accept(bbo("MKT-1", 1_400L, 600_000L));
        store.accept(bbo("MKT-1", 1_900L, 550_000L));
        store.accept(bbo("MKT-1", 2_500L, 700_000L));
        store.accept(bbo("MKT-1", 3_000L, 650_000L));

        List<Bar> bars = store.bars("MKT-1", 0L, 5_000L, BarResolution.S1);
        assertEquals(3, bars.size());

        Bar first = bars.get(0);
        assertEquals(1_000L, first.openTimeMs());
        assertEquals(0.5, first.open(), 1e-9);
        assertEquals(0.6, first.high(), 1e-9);
        assertEquals(0.5, first.low(), 1e-9);
        assertEquals(0.55, first.close(), 1e-9);
        assertEquals(3L, first.sampleCount());

        Bar second = bars.get(1);
        assertEquals(2_000L, second.openTimeMs());
        assertEquals(0.7, second.open(), 1e-9);
        assertEquals(0.7, second.close(), 1e-9);
        assertEquals(1L, second.sampleCount());

        Bar third = bars.get(2);
        assertEquals(3_000L, third.openTimeMs());
        assertEquals(0.65, third.open(), 1e-9);
        assertEquals(1L, third.sampleCount());
    }

    @Test
    void emptyBucketsAreSkipped() {
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(bbo("MKT-1", 1_000L, 500_000L));
        store.accept(bbo("MKT-1", 4_500L, 600_000L));

        List<Bar> bars = store.bars("MKT-1", 0L, 10_000L, BarResolution.S1);
        assertEquals(2, bars.size());
        assertEquals(1_000L, bars.get(0).openTimeMs());
        assertEquals(4_000L, bars.get(1).openTimeMs());
    }

    @Test
    void fromToRangeFiltersOutOfBandSamples() {
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(bbo("MKT-1", 500L, 400_000L));
        store.accept(bbo("MKT-1", 1_500L, 500_000L));
        store.accept(bbo("MKT-1", 2_500L, 600_000L));
        store.accept(bbo("MKT-1", 3_500L, 700_000L));

        List<Bar> bars = store.bars("MKT-1", 1_000L, 3_000L, BarResolution.S1);
        assertEquals(2, bars.size());
        assertEquals(1_000L, bars.get(0).openTimeMs());
        assertEquals(2_000L, bars.get(1).openTimeMs());
        for (Bar bar : bars) {
            assertTrue(bar.openTimeMs() >= 1_000L && bar.openTimeMs() <= 3_000L);
        }
    }

    @Test
    void unknownMarketReturnsEmpty() {
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        assertTrue(store.bars("MKT-MISSING", 0L, 1_000L, BarResolution.M1).isEmpty());
    }

    @Test
    void fallsBackToTickerSnapshotBarsWhenBboIsAbsent() {
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(ticker("MKT-1", 1_000L, 420_000L, null, null));
        store.accept(ticker("MKT-1", 1_500L, null, 430_000L, 450_000L));

        FrontendFeatureStore.BarSeries series = store.barSeries("MKT-1", 0L, 2_000L, BarResolution.S1);

        assertEquals("ticker_snapshot", series.source());
        assertEquals(1, series.bars().size());
        assertEquals(0.42, series.bars().get(0).open(), 1e-9);
        assertEquals(0.44, series.bars().get(0).close(), 1e-9);
    }

    @Test
    void fallsBackToTradeTapeBarsWhenTickerSnapshotIsAbsent() {
        FrontendFeatureStore store = new FrontendFeatureStore(100, 100);
        store.accept(trade("MKT-1", 1_000L, 630_000L, null));
        store.accept(trade("MKT-1", 2_000L, null, 300_000L));

        FrontendFeatureStore.BarSeries series = store.barSeries("MKT-1", 0L, 3_000L, BarResolution.S1);

        assertEquals("trade_tape", series.source());
        assertEquals(2, series.bars().size());
        assertEquals(0.63, series.bars().get(0).close(), 1e-9);
        assertEquals(0.70, series.bars().get(1).close(), 1e-9);
    }

    private static FeatureOutput bbo(String market, long ts, long midpoint) {
        return new FeatureOutput(
            FrontendFeatureStore.BBO_FEATURE,
            FrontendFeatureStore.BBO_FEATURE,
            market,
            ts,
            "evt-" + ts,
            Map.of(
                "bid_price_micros", midpoint - 50_000L,
                "ask_price_micros", midpoint + 50_000L,
                "midpoint_micros", midpoint
            )
        );
    }

    private static FeatureOutput ticker(String market, long ts, Long price, Long bid, Long ask) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        if (price != null) {
            values.put("price_micros", price);
        }
        if (bid != null) {
            values.put("yes_bid_micros", bid);
        }
        if (ask != null) {
            values.put("yes_ask_micros", ask);
        }
        return new FeatureOutput(
            FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE,
            FrontendFeatureStore.TICKER_SNAPSHOT_FEATURE,
            market,
            ts,
            "ticker-" + ts,
            values
        );
    }

    private static FeatureOutput trade(String market, long ts, Long yesPrice, Long noPrice) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        if (yesPrice != null) {
            values.put("yes_price_micros", yesPrice);
        }
        if (noPrice != null) {
            values.put("no_price_micros", noPrice);
        }
        return new FeatureOutput(
            FrontendFeatureStore.TRADE_TAPE_FEATURE,
            FrontendFeatureStore.TRADE_TAPE_FEATURE,
            market,
            ts,
            "trade-" + ts,
            values
        );
    }
}
