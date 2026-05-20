package edu.illinois.group8.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.MarketMetadataStore;
import edu.illinois.group8.wrapper.RequestParameters;

import java.util.List;
import java.util.Objects;

public final class KalshiCatalogSyncService {
    private final HistoricalBackfillClient client;
    private final MarketMetadataMapper mapper;
    private final MarketMetadataStore store;
    private final ObjectMapper jsonMapper;

    public KalshiCatalogSyncService(HistoricalBackfillClient client, MarketMetadataStore store) {
        this(client, store, new MarketMetadataMapper(), new JsonCanonicalSerializer().mapper());
    }

    KalshiCatalogSyncService(
        HistoricalBackfillClient client,
        MarketMetadataStore store,
        MarketMetadataMapper mapper,
        ObjectMapper jsonMapper
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.store = store;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    public CatalogSyncSummary run(CatalogSyncRequest request) {
        CatalogSyncRequest normalized = Objects.requireNonNull(request, "request");
        if (!normalized.dryRun() && store == null) {
            throw new IllegalArgumentException("MarketMetadataStore is required when dry_run=false");
        }

        int pagesFetched = 0;
        int marketsDiscovered = 0;
        int marketsSelected = 0;
        int rowsUpserted = 0;
        int dryRunRows = 0;
        String cursor = "";
        boolean hasMore = false;

        for (int page = 0; page < normalized.maxPages(); page++) {
            String rawPayload = client.getMarkets(params(normalized, cursor));
            if (rawPayload == null || rawPayload.isBlank()) {
                throw new IllegalStateException("Kalshi markets response was empty");
            }
            pagesFetched++;
            List<MarketMetadata> markets = mapper.fromMarketsResponse(rawPayload);
            marketsDiscovered += markets.size();
            for (MarketMetadata metadata : markets) {
                if (normalized.maxTickers() > 0 && marketsSelected >= normalized.maxTickers()) {
                    break;
                }
                marketsSelected++;
                if (normalized.dryRun()) {
                    dryRunRows++;
                } else {
                    try {
                        store.upsertMarketMetadata(metadata);
                        rowsUpserted++;
                    } catch (Exception e) {
                        throw new IllegalStateException(
                            "Failed to upsert market metadata for " + metadata.marketTicker(),
                            e
                        );
                    }
                }
            }
            cursor = cursor(rawPayload);
            hasMore = !cursor.isBlank();
            if (!hasMore || (normalized.maxTickers() > 0 && marketsSelected >= normalized.maxTickers())) {
                break;
            }
        }

        return new CatalogSyncSummary(
            pagesFetched,
            marketsDiscovered,
            marketsSelected,
            rowsUpserted,
            dryRunRows,
            0,
            cursor,
            hasMore
        );
    }

    private RequestParameters params(CatalogSyncRequest request, String cursor) {
        RequestParameters params = new RequestParameters();
        params.addParam("limit", request.limit());
        if (!request.marketStatus().isBlank()) {
            params.addParam("status", request.marketStatus());
        }
        if (!request.seriesTicker().isBlank()) {
            params.addParam("series_ticker", request.seriesTicker());
        }
        if (!request.mveFilter().isBlank()) {
            params.addParam("mve_filter", request.mveFilter());
        }
        if (cursor != null && !cursor.isBlank()) {
            params.addParam("cursor", cursor);
        }
        return params;
    }

    private String cursor(String rawPayload) {
        try {
            return jsonMapper.readTree(rawPayload).path("cursor").asText("");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Kalshi markets cursor JSON", e);
        }
    }
}
