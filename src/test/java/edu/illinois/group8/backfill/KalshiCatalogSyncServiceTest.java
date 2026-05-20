package edu.illinois.group8.backfill;

import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.MarketMetadataStore;
import edu.illinois.group8.wrapper.RequestParameters;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KalshiCatalogSyncServiceTest {
    @Test
    void paginatesAndUpsertsCatalogOnlyMetadata() {
        FakeClient client = new FakeClient(
            marketsPage("cursor-2", market("MKT-1", "EVT-1", "SERIES-1")),
            marketsPage("", market("MKT-2", "EVT-2", "SERIES-1"))
        );
        CollectingStore store = new CollectingStore();
        KalshiCatalogSyncService service = new KalshiCatalogSyncService(client, store);

        CatalogSyncSummary summary = service.run(new CatalogSyncRequest(
            false,
            "SERIES-1",
            "open",
            100,
            2,
            0,
            "exclude"
        ));

        assertEquals(2, summary.pagesFetched());
        assertEquals(2, summary.marketsDiscovered());
        assertEquals(2, summary.marketsSelected());
        assertEquals(2, summary.rowsUpserted());
        assertEquals(0, summary.failures());
        assertEquals("", summary.nextCursor());
        assertEquals(List.of("MKT-1", "MKT-2"), store.tickers());
        assertEquals(2, client.marketRequests.size());
        assertEquals(100, client.marketRequests.get(0).getParams().get("limit"));
        assertEquals("open", client.marketRequests.get(0).getParams().get("status"));
        assertEquals("SERIES-1", client.marketRequests.get(0).getParams().get("series_ticker"));
        assertEquals("exclude", client.marketRequests.get(0).getParams().get("mve_filter"));
        assertTrue(!client.marketRequests.get(0).getParams().containsKey("cursor"));
        assertEquals("cursor-2", client.marketRequests.get(1).getParams().get("cursor"));
        assertEquals(0, client.tradeRequests);
        assertEquals(0, client.orderbookRequests);
        assertEquals(0, client.candlestickRequests);
    }

    @Test
    void dryRunMapsButDoesNotWriteStore() {
        FakeClient client = new FakeClient(marketsPage("", market("MKT-DRY", "EVT-DRY", "SERIES-DRY")));
        CollectingStore store = new CollectingStore();

        CatalogSyncSummary summary = new KalshiCatalogSyncService(client, store)
            .run(new CatalogSyncRequest(true, "", "open", 50, 1, 0, ""));

        assertEquals(1, summary.pagesFetched());
        assertEquals(1, summary.marketsDiscovered());
        assertEquals(1, summary.marketsSelected());
        assertEquals(0, summary.rowsUpserted());
        assertEquals(1, summary.dryRunRows());
        assertEquals(0, store.rows.size());
    }

    @Test
    void maxTickersCapsWritesAcrossPages() {
        FakeClient client = new FakeClient(
            marketsPage("next", market("MKT-1", "EVT-1", "SERIES"), market("MKT-2", "EVT-2", "SERIES")),
            marketsPage("", market("MKT-3", "EVT-3", "SERIES"))
        );
        CollectingStore store = new CollectingStore();

        CatalogSyncSummary summary = new KalshiCatalogSyncService(client, store)
            .run(new CatalogSyncRequest(false, "", "open", 100, 2, 1, ""));

        assertEquals(1, summary.pagesFetched());
        assertEquals(2, summary.marketsDiscovered());
        assertEquals(1, summary.marketsSelected());
        assertEquals(1, summary.rowsUpserted());
        assertEquals(List.of("MKT-1"), store.tickers());
        assertEquals(1, client.marketRequests.size());
    }

    @Test
    void nonDryRunRequiresStore() {
        FakeClient client = new FakeClient(marketsPage("", market("MKT", "EVT", "SERIES")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            new KalshiCatalogSyncService(client, null)
                .run(new CatalogSyncRequest(false, "", "open", 100, 1, 0, "")));

        assertTrue(error.getMessage().contains("MarketMetadataStore"));
    }

    @Test
    void emptyMarketsResponseFailsFast() {
        FakeClient client = new FakeClient(" ");

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            new KalshiCatalogSyncService(client, null)
                .run(new CatalogSyncRequest(true, "", "open", 100, 1, 0, "")));

        assertTrue(error.getMessage().contains("empty"));
    }

    @Test
    void requestRejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () ->
            new CatalogSyncRequest(true, "", "open", 0, 1, 0, ""));
        assertThrows(IllegalArgumentException.class, () ->
            new CatalogSyncRequest(true, "", "open", 1, 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () ->
            new CatalogSyncRequest(true, "", "open", 1, 1, -1, ""));
    }

    private static String marketsPage(String cursor, String... markets) {
        return """
            {"markets":[%s],"cursor":"%s"}
            """.formatted(String.join(",", markets), cursor);
    }

    private static String market(String ticker, String eventTicker, String seriesTicker) {
        return """
            {"ticker":"%s","event_ticker":"%s","series_ticker":"%s","status":"open","open_time":"2026-05-20T00:00:00Z","rules":{"x":1}}
            """.formatted(ticker, eventTicker, seriesTicker);
    }

    private static final class FakeClient implements HistoricalBackfillClient {
        private final List<String> pages;
        private final List<RequestParameters> marketRequests = new ArrayList<>();
        private int tradeRequests;
        private int orderbookRequests;
        private int candlestickRequests;

        private FakeClient(String... pages) {
            this.pages = List.of(pages);
        }

        @Override
        public String getMarkets(RequestParameters params) {
            marketRequests.add(params);
            return pages.get(marketRequests.size() - 1);
        }

        @Override
        public String getTrades(RequestParameters params) {
            tradeRequests++;
            throw new AssertionError("catalog sync must not fetch trades");
        }

        @Override
        public String getMarketOrderbook(String ticker, RequestParameters params) {
            orderbookRequests++;
            throw new AssertionError("catalog sync must not fetch order books");
        }

        @Override
        public String getMarketCandlesticks(
            String ticker,
            String seriesTicker,
            Integer startTs,
            Integer endTs,
            Integer periodInterval
        ) {
            candlestickRequests++;
            throw new AssertionError("catalog sync must not fetch candlesticks");
        }
    }

    private static final class CollectingStore implements MarketMetadataStore {
        private final List<MarketMetadata> rows = new ArrayList<>();

        @Override
        public void upsertMarketMetadata(MarketMetadata metadata) {
            rows.add(metadata);
        }

        private List<String> tickers() {
            return rows.stream().map(MarketMetadata::marketTicker).toList();
        }
    }
}
