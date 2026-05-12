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
}
