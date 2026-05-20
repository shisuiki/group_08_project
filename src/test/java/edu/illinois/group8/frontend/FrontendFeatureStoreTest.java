package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.storage.db.FeatureOutputRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void latestIgnoresOlderEventTimestampAcceptedLater() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        store.accept(row("feature-new", "2026-05-20T00:00:01Z", bbo("MKT-1", 2_000L, 600_000L)));
        store.accept(row("feature-old", "2026-05-20T00:00:02Z", bbo("MKT-1", 1_000L, 500_000L)));

        Optional<FeatureOutput> latest = store.latest("MKT-1", FrontendFeatureStore.BBO_FEATURE);

        assertTrue(latest.isPresent());
        assertEquals(2_000L, latest.get().eventTsMs());
        assertEquals(600_000L, latest.get().values().get("midpoint_micros"));
        assertEquals(2L, store.sequence());
        assertEquals(List.of(1_000L, 2_000L), store.snapshot("MKT-1", FrontendFeatureStore.BBO_FEATURE).stream()
            .map(FeatureOutput::eventTsMs)
            .toList());
    }

    @Test
    void latestUsesCreatedAtAndFeatureEventIdWhenEventTimestampsTie() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        store.accept(row("feature-1", "2026-05-20T00:00:01Z", bbo("MKT-1", 2_000L, 500_000L)));
        store.accept(row("feature-2", "2026-05-20T00:00:02Z", bbo("MKT-1", 2_000L, 600_000L)));
        store.accept(row("feature-0", "2026-05-20T00:00:02Z", bbo("MKT-1", 2_000L, 550_000L)));

        Optional<FeatureOutput> latest = store.latest("MKT-1", FrontendFeatureStore.BBO_FEATURE);

        assertTrue(latest.isPresent());
        assertEquals(600_000L, latest.get().values().get("midpoint_micros"));
    }

    @Test
    void blankMarketTickerIsIgnored() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        store.accept(bbo("", 100L, 5_000_000L));
        store.accept(bbo(null, 100L, 5_000_000L));
        assertTrue(store.symbols().isEmpty());
        assertEquals(0L, store.totalAccepted());
        assertEquals(0L, store.sequence());
    }

    @Test
    void sequenceAdvancesOnlyForAcceptedFeatures() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);

        assertEquals(0L, store.sequence());
        store.accept((FeatureOutput) null);
        store.accept(bbo("", 100L, 5_000_000L));
        assertEquals(0L, store.sequence());

        store.accept(bbo("MKT-1", 100L, 5_000_000L));
        store.accept(trade("MKT-1", 101L, "T-1"));

        assertEquals(2L, store.sequence());
        assertEquals(2L, store.totalAccepted());
    }

    @Test
    void latestFreshnessTracksGlobalLatestFeatureAndStoreSequence() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        store.accept(bbo("MKT-1", 2_000L, 600_000L));
        store.accept(trade("MKT-2", 3_000L, "T-1"));
        store.accept(row("feature-old", "2026-05-20T00:00:03Z", bbo("MKT-3", 1_000L, 500_000L)));

        FrontendFeatureStore.DataFreshness freshness = store.latestFreshness(3_500L);

        assertEquals(3_000L, freshness.latestEventTsMs());
        assertEquals(500L, freshness.latestEventAgeMs());
        assertEquals("MKT-2", freshness.symbol());
        assertEquals("feature.trade_tape", freshness.featureName());
        assertEquals("evt-t-3000", freshness.sourceEventId());
        assertEquals("live", freshness.sourceKind());
        assertFalse(freshness.synthetic());
        assertTrue(freshness.liveDataObserved());
        assertEquals(3L, freshness.storeSequence());
    }

    @Test
    void latestFreshnessIsEmptyBeforeAcceptedFeatures() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);

        FrontendFeatureStore.DataFreshness freshness = store.latestFreshness(3_500L);

        assertEquals(null, freshness.latestEventTsMs());
        assertEquals(null, freshness.latestEventAgeMs());
        assertEquals(null, freshness.symbol());
        assertEquals(null, freshness.featureName());
        assertEquals(null, freshness.sourceEventId());
        assertEquals("unknown", freshness.sourceKind());
        assertFalse(freshness.synthetic());
        assertFalse(freshness.liveDataObserved());
        assertEquals(0L, freshness.storeSequence());
    }

    @Test
    void latestFreshnessPrefersNonSmokeWhenSmokeHasNewerEventTs() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        store.accept(bbo("MKT-LIVE", 2_000L, 600_000L));
        store.accept(smokeBbo("LIVE-PRODUCT-SMOKE-run-1", 3_000L, 700_000L));

        FrontendFeatureStore.DataFreshness freshness = store.latestFreshness(3_500L);

        assertEquals("MKT-LIVE", freshness.symbol());
        assertEquals(2_000L, freshness.latestEventTsMs());
        assertEquals("evt-2000", freshness.sourceEventId());
        assertEquals("live", freshness.sourceKind());
        assertFalse(freshness.synthetic());
        assertTrue(freshness.liveDataObserved());
        assertEquals(2L, freshness.storeSequence());
    }

    @Test
    void latestFreshnessReportsSmokeWhenOnlySmokeExists() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        store.accept(smokeBbo("LIVE-PRODUCT-SMOKE-run-1", 3_000L, 700_000L));

        FrontendFeatureStore.DataFreshness freshness = store.latestFreshness(3_500L);

        assertEquals("LIVE-PRODUCT-SMOKE-run-1", freshness.symbol());
        assertEquals(3_000L, freshness.latestEventTsMs());
        assertEquals("live-product-smoke-3000", freshness.sourceEventId());
        assertEquals("smoke", freshness.sourceKind());
        assertTrue(freshness.synthetic());
        assertFalse(freshness.liveDataObserved());
        assertEquals(1L, freshness.storeSequence());
    }

    @Test
    void latestFreshnessReportsDemoReplayAsSyntheticNotLive() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        store.accept(demoBbo("DEMO-DBPRIMARY-26MAY19-T50", 3_000L, 700_000L));

        FrontendFeatureStore.DataFreshness freshness = store.latestFreshness(3_500L);

        assertEquals("DEMO-DBPRIMARY-26MAY19-T50", freshness.symbol());
        assertEquals(3_000L, freshness.latestEventTsMs());
        assertEquals("demo-db-primary-canonical-bbo-3000", freshness.sourceEventId());
        assertEquals("demo", freshness.sourceKind());
        assertTrue(freshness.synthetic());
        assertFalse(freshness.liveDataObserved());
        assertEquals(1L, freshness.storeSequence());
    }

    @Test
    void latestFreshnessPrefersLiveOverNewerDemoReplay() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        store.accept(bbo("MKT-LIVE", 2_000L, 600_000L));
        store.accept(demoBbo("DEMO-DBPRIMARY-26MAY19-T50", 3_000L, 700_000L));

        FrontendFeatureStore.DataFreshness freshness = store.latestFreshness(3_500L);

        assertEquals("MKT-LIVE", freshness.symbol());
        assertEquals(2_000L, freshness.latestEventTsMs());
        assertEquals("evt-2000", freshness.sourceEventId());
        assertEquals("live", freshness.sourceKind());
        assertFalse(freshness.synthetic());
        assertTrue(freshness.liveDataObserved());
        assertEquals(2L, freshness.storeSequence());
    }

    @Test
    void latestFreshnessFollowsRetainedMarketAfterSymbolEviction() {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 1);
        store.accept(bbo("MKT-OLD", 10_000L, 600_000L));
        store.accept(bbo("MKT-NEW", 1_000L, 500_000L));

        FrontendFeatureStore.DataFreshness freshness = store.latestFreshness(12_000L);

        assertEquals("MKT-NEW", freshness.symbol());
        assertEquals(1_000L, freshness.latestEventTsMs());
        assertEquals(11_000L, freshness.latestEventAgeMs());
        assertEquals(List.of("MKT-NEW"), List.copyOf(store.symbols()));
    }

    @Test
    void waitForSequenceAfterReturnsWhenAcceptAdvancesSequence() throws Exception {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> waiter = executor.submit(() -> store.waitForSequenceAfter(0L, 1_000L));
            Thread.sleep(25L);
            assertFalse(waiter.isDone());

            store.accept(bbo("MKT-1", 100L, 5_000_000L));

            assertEquals(1L, waiter.get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void waitForSequenceAfterTimesOutWithoutAdvance() throws Exception {
        FrontendFeatureStore store = new FrontendFeatureStore(10, 100);
        long startedNs = System.nanoTime();

        long sequence = store.waitForSequenceAfter(0L, 25L);

        assertEquals(0L, sequence);
        assertNotEquals(0L, System.nanoTime() - startedNs);
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

    static FeatureOutput smokeBbo(String market, long ts, long midpoint) {
        return new FeatureOutput(
            FrontendFeatureStore.BBO_FEATURE,
            FrontendFeatureStore.BBO_FEATURE,
            market,
            ts,
            "live-product-smoke-" + ts,
            Map.of(
                "bid_price_micros", midpoint - 100_000L,
                "ask_price_micros", midpoint + 100_000L,
                "midpoint_micros", midpoint
            )
        );
    }

    static FeatureOutput demoBbo(String market, long ts, long midpoint) {
        return new FeatureOutput(
            FrontendFeatureStore.BBO_FEATURE,
            FrontendFeatureStore.BBO_FEATURE,
            market,
            ts,
            "demo-db-primary-canonical-bbo-" + ts,
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

    private static FeatureOutputRow row(String featureEventId, String createdAt, FeatureOutput output) {
        return new FeatureOutputRow(featureEventId, Instant.parse(createdAt), output);
    }
}
