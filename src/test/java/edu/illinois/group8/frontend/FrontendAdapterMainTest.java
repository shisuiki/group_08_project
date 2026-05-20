package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.CanonicalEnvelopeSource;
import edu.illinois.group8.feature.DbCanonicalEnvelopeSource;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.feature.RecordingCanonicalEnvelopeSource;
import edu.illinois.group8.storage.db.FeatureOutputReadRequest;
import edu.illinois.group8.storage.db.FeatureOutputRow;
import edu.illinois.group8.storage.db.JdbcMarketMetadataReader;
import edu.illinois.group8.storage.db.JdbcSemanticMarketMetadataReader;
import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.MarketMetadataReadRequest;
import edu.illinois.group8.storage.db.OperatorSemanticMetadataStatus;
import edu.illinois.group8.storage.db.SemanticMarketMetadataReadRequest;
import edu.illinois.group8.storage.db.SemanticMarketMetadataReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAdapterMainTest {
    @TempDir
    Path tempDir;

    @Test
    void dbSourceMissingUrlFailsFast() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_SOURCE", "db",
            "FRONTEND_ADAPTER_DB_URL", ""
        ));

        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FrontendAdapterMain.buildSource(config)
        );

        assertTrue(thrown.getMessage().contains("FRONTEND_ADAPTER_DB_URL"));
        assertTrue(thrown.getMessage().contains("DB_WRITER_DATABASE_URL"));
    }

    @Test
    void defaultDbSourceWithWriterUrlBuildsWithoutOpeningConnection() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://unused/kalshi",
            "DB_WRITER_DATABASE_USER", "writer",
            "DB_WRITER_DATABASE_PASSWORD", "secret"
        ));

        CanonicalEnvelopeSource source = FrontendAdapterMain.buildSource(config);

        assertInstanceOf(DbCanonicalEnvelopeSource.class, source);
    }

    @Test
    void explicitRecordingSourceDoesNotRequireDatabaseUrl() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_SOURCE", "recording",
            "FRONTEND_ADAPTER_RECORDING_ROOT", tempDir.toString(),
            "FRONTEND_ADAPTER_DB_URL", ""
        ));

        CanonicalEnvelopeSource source = FrontendAdapterMain.buildSource(config);

        assertInstanceOf(RecordingCanonicalEnvelopeSource.class, source);
    }

    @Test
    void featureOutputModeRequiresDatabaseUrl() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_DB_URL", ""
        ));

        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FrontendAdapterMain.buildFeatureOutputReader(config)
        );

        assertTrue(thrown.getMessage().contains("FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs"));
    }

    @Test
    void latestMarketStateModeRequiresDatabaseUrl() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "latest_market_state",
            "FRONTEND_ADAPTER_DB_URL", ""
        ));

        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FrontendAdapterMain.buildLatestMarketStateReader(config)
        );

        assertTrue(thrown.getMessage().contains("FRONTEND_ADAPTER_FEATURE_SOURCE=latest_market_state"));
    }

    @Test
    void semanticMetadataStatusSupplierIsDisabledWithoutDatabase() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_SEMANTIC_METADATA_STATUS_SOURCE", "db",
            "LLM_METADATA_MODEL", "m",
            "LLM_METADATA_FALLBACK_MODEL", "f",
            "LLM_METADATA_TAXONOMY_VERSION", "tax"
        ));

        OperatorSemanticMetadataStatus status =
            FrontendAdapterMain.buildOperatorSemanticMetadataStatusSupplier(config).get();

        assertEquals("disabled", status.status());
        assertFalse(status.configured());
        assertEquals("m", status.model());
        assertEquals("f", status.fallbackModel());
        assertEquals("tax", status.taxonomyVersion());
    }

    @Test
    void semanticMetadataStatusSupplierBuildsReaderWithoutOpeningConnection() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://unused/kalshi",
            "FRONTEND_ADAPTER_DB_USER", "frontend",
            "FRONTEND_ADAPTER_DB_PASSWORD", "secret",
            "FRONTEND_ADAPTER_SEMANTIC_METADATA_STATUS_SOURCE", "db"
        ));

        java.util.function.Supplier<OperatorSemanticMetadataStatus> supplier =
            FrontendAdapterMain.buildOperatorSemanticMetadataStatusSupplier(config);

        assertTrue(supplier != null);
    }

    @Test
    void semanticMarketMetadataReaderIsDisabledWithoutDatabase() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_SEMANTIC_METADATA_STATUS_SOURCE", "db",
            "LLM_METADATA_TAXONOMY_VERSION", "tax-v1"
        ));

        SemanticMarketMetadataReader reader = FrontendAdapterMain.buildSemanticMarketMetadataReader(config);

        assertEquals(List.of(), reader.read(SemanticMarketMetadataReadRequest.defaultForTaxonomy("tax-v1")));
    }

    @Test
    void semanticMarketMetadataReaderBuildsWithoutOpeningConnection() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://unused/kalshi",
            "FRONTEND_ADAPTER_DB_USER", "frontend",
            "FRONTEND_ADAPTER_DB_PASSWORD", "secret",
            "FRONTEND_ADAPTER_SEMANTIC_METADATA_STATUS_SOURCE", "db"
        ));

        SemanticMarketMetadataReader reader = FrontendAdapterMain.buildSemanticMarketMetadataReader(config);

        assertInstanceOf(JdbcSemanticMarketMetadataReader.class, reader);
    }

    @Test
    void featureOutputReadRequestUsesConfiguredModulesAndLimit() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_MODULES", "bbo,trade_tape",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS", "50"
        ));

        FeatureOutputReadRequest request = FrontendAdapterMain.featureOutputReadRequest(config);

        assertEquals(List.of("feature.bbo", "feature.trade_tape"), request.featureNames());
        assertEquals(50, request.maxRows());
    }

    @Test
    void seedFeatureOutputsAcceptsOldestFirstSoLatestIsNewest() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS", "10"
        ));
        FrontendFeatureStore store = new FrontendFeatureStore(10, 10);
        List<FeatureOutputReadRequest> requests = new ArrayList<>();

        int seeded = FrontendAdapterMain.seedFeatureOutputs(config, store, request -> {
            requests.add(request);
            return List.of(
                output(2_000L, "new"),
                output(1_000L, "old")
            );
        });

        assertEquals(2, seeded);
        assertEquals(1, requests.size());
        assertEquals(10, requests.get(0).maxRows());
        assertEquals("new", store.latest("MKT-1", "feature.bbo").orElseThrow().sourceEventId());
        assertEquals(List.of("old", "new"), store.snapshot("MKT-1", "feature.bbo").stream()
            .map(FeatureOutput::sourceEventId)
            .toList());
    }

    @Test
    void featureOutputRefreshUsesCursorAndKeepsStoreOnReaderError() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS", "10",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS", "3"
        ));
        FrontendFeatureStore store = new FrontendFeatureStore(10, 10);
        List<FeatureOutputReadRequest> requests = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        FeatureOutputRefreshService service = new FeatureOutputRefreshService(config, store, request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> List.of(
                    row("feature-2", "2026-05-20T00:00:02Z", 2_000L, "new"),
                    row("feature-1", "2026-05-20T00:00:01Z", 1_000L, "old")
                );
                case 1 -> List.of(row("feature-3", "2026-05-20T00:00:03Z", 3_000L, "refresh"));
                default -> throw new IllegalStateException("db unavailable");
            };
        });

        assertEquals(2, service.seedOnce());
        assertEquals("new", store.latest("MKT-1", "feature.bbo").orElseThrow().sourceEventId());
        assertEquals(1, requests.size());
        assertFalse(requests.get(0).ascending());
        assertEquals(10, requests.get(0).maxRows());

        assertEquals(1, service.refreshOnce());
        assertEquals(2, requests.size());
        FeatureOutputReadRequest refreshRequest = requests.get(1);
        assertTrue(refreshRequest.ascending());
        assertEquals(3, refreshRequest.maxRows());
        assertEquals("feature-2", refreshRequest.after().featureEventId());
        assertEquals(Instant.parse("2026-05-20T00:00:02Z"), refreshRequest.after().createdAt());
        assertEquals("refresh", store.latest("MKT-1", "feature.bbo").orElseThrow().sourceEventId());
        assertEquals(3L, service.status().totalLoaded());
        assertEquals(1, service.status().lastRowCount());

        assertEquals(0, service.refreshOnce());
        assertEquals(1L, service.status().refreshErrors());
        assertTrue(service.status().lastError().contains("db unavailable"));
        assertEquals("refresh", store.latest("MKT-1", "feature.bbo").orElseThrow().sourceEventId());
    }

    @Test
    void latestMarketStateRefreshDoesNotGrowSequenceWhenReaderReturnsNoRowsAfterCursor() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "latest_market_state",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS", "10",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS", "3"
        ));
        FrontendFeatureStore store = new FrontendFeatureStore(10, 10);
        AtomicInteger calls = new AtomicInteger();
        FeatureOutputRefreshService service = new FeatureOutputRefreshService(config, store, request -> {
            return switch (calls.getAndIncrement()) {
                case 0 -> List.of(row("MKT-1", "2026-05-20T00:00:01Z", 1_000L, "seed"));
                default -> List.of();
            };
        });

        assertEquals(1, service.seedOnce());
        long seededSequence = store.sequence();

        assertEquals(0, service.refreshOnce());

        assertEquals(seededSequence, store.sequence());
        assertEquals(1L, service.status().totalLoaded());
        assertEquals(0, service.status().lastRowCount());
    }

    @Test
    void featureOutputRefreshWakesStoreSequenceWaiters() throws Exception {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS", "10",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS", "3"
        ));
        FrontendFeatureStore store = new FrontendFeatureStore(10, 10);
        AtomicInteger calls = new AtomicInteger();
        FeatureOutputRefreshService service = new FeatureOutputRefreshService(config, store, ignored -> {
            return switch (calls.getAndIncrement()) {
                case 0 -> List.of(row("feature-1", "2026-05-20T00:00:01Z", 1_000L, "seed"));
                case 1 -> List.of(row("feature-2", "2026-05-20T00:00:02Z", 2_000L, "refresh"));
                default -> List.of();
            };
        });
        assertEquals(1, service.seedOnce());
        long after = store.sequence();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> waiter = executor.submit(() -> store.waitForSequenceAfter(after, 1_000L));
            Thread.sleep(25L);
            assertFalse(waiter.isDone());

            assertEquals(1, service.refreshOnce());

            assertEquals(after + 1L, waiter.get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void featureOutputRefreshCloseStopsBackgroundLoop() throws Exception {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS", "1000"
        ));
        FrontendFeatureStore store = new FrontendFeatureStore(10, 10);
        CountDownLatch slept = new CountDownLatch(1);
        FeatureOutputRefreshService service = new FeatureOutputRefreshService(
            config,
            store,
            ignored -> List.of(),
            millis -> {
                slept.countDown();
                Thread.sleep(millis);
            }
        );

        service.seedOnce();
        service.start();
        assertTrue(slept.await(2, TimeUnit.SECONDS));

        service.close();

        assertFalse(service.status().running());
    }

    @Test
    void explicitMetadataDbModeRequiresDatabaseUrl() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_METADATA_SOURCE", "db",
            "FRONTEND_ADAPTER_DB_URL", ""
        ));

        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FrontendAdapterMain.buildMarketMetadataReader(config)
        );

        assertTrue(thrown.getMessage().contains("FRONTEND_ADAPTER_METADATA_SOURCE=db"));
    }

    @Test
    void metadataReaderBuildsWithoutOpeningConnection() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_METADATA_SOURCE", "db",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://unused/kalshi"
        ));

        assertInstanceOf(JdbcMarketMetadataReader.class, FrontendAdapterMain.buildMarketMetadataReader(config));
    }

    @Test
    void autoMetadataWithoutDbUrlIsDisabled() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_METADATA_SOURCE", "auto",
            "FRONTEND_ADAPTER_DB_URL", ""
        ));

        FrontendMarketMetadataCatalog catalog = FrontendAdapterMain.loadMarketMetadata(
            config,
            () -> {
                throw new AssertionError("reader should not be built");
            }
        );

        assertEquals(FrontendMarketMetadataCatalog.LoadStatus.DISABLED, catalog.loadStatus());
        assertEquals("auto", catalog.source());
    }

    @Test
    void autoMetadataFailureDegradesCatalogStatus() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_METADATA_SOURCE", "auto",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://unused/kalshi"
        ));

        FrontendMarketMetadataCatalog catalog = FrontendAdapterMain.loadMarketMetadata(
            config,
            () -> request -> {
                throw new IllegalStateException("metadata unavailable");
            }
        );

        assertEquals(FrontendMarketMetadataCatalog.LoadStatus.UNAVAILABLE, catalog.loadStatus());
        assertEquals("auto", catalog.source());
        assertTrue(catalog.error().contains("metadata unavailable"));
    }

    @Test
    void lightweightFrontendUsesQuoteUpdatesWithStaleLoopGuard() throws Exception {
        String app = Files.readString(Path.of("frontend/tradingview-lightweight/app.js"));

        assertTrue(app.contains("/quotes/updates?symbols="));
        assertTrue(app.contains("quotesLoopGeneration"));
        assertTrue(app.contains("AbortController"));
        assertTrue(app.contains("staleQuoteLoop"));
        assertTrue(app.contains("startQuotesFallback"));
        assertTrue(app.contains("QUOTES_UPDATE_ERROR_LIMIT"));
        assertTrue(app.contains("window.location.origin"));
        assertTrue(app.contains("/quotes?symbols="));
        assertTrue(app.contains("nextSequence < quoteSequence"));
        assertTrue(app.contains("MARKET_CATALOG_LIMIT"));
        assertTrue(app.contains("'/markets?' + params.join('&')"));
        assertTrue(app.contains("market-search"));
        assertTrue(app.contains("market-status-filter"));
        assertTrue(app.contains("market-search-apply"));
        assertTrue(app.contains("market-state"));
        assertTrue(app.contains("markets.markets.length > 0"));
        assertTrue(app.contains("/features?"));
        assertTrue(app.contains("/health"));
        assertTrue(app.contains("/ops/pipeline"));
        assertTrue(app.contains("/ops/latency"));
        assertTrue(app.contains("/operator/catalog/sync"));
        assertTrue(app.contains("/operator/catalog/sync-status"));
        assertTrue(app.contains("/operator/semantic-metadata/run"));
        assertTrue(app.contains("/operator/semantic-metadata/run-status"));
        assertTrue(app.contains("/operator/demo-orchestrator/run"));
        assertTrue(app.contains("/operator/demo-orchestrator/run-status"));
        assertTrue(app.contains("/api/semantic-metadata/treemap?"));
        assertTrue(app.contains("/api/semantic-metadata/markets?"));
        assertTrue(app.contains("SEMANTIC_MAP_DEFAULT_LIMIT"));
        assertTrue(app.contains("layoutSemanticLeafTreemap"));
        assertTrue(app.contains("semanticRenderableLeaves"));
        assertTrue(app.contains("buildDemoRunRequest"));
        assertTrue(app.contains("applyRoleVisibility"));
        assertTrue(app.contains("dataFreshnessBadgeText"));
    }

    @Test
    void marketMetadataReadRequestUsesConfiguredLimit() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_METADATA_MAX_ROWS", "250"
        ));

        MarketMetadataReadRequest request = FrontendAdapterMain.marketMetadataReadRequest(config);

        assertEquals(250, request.maxRows());
    }

    @Test
    void metadataCatalogLoadsRowsFromReader() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_METADATA_SOURCE", "db",
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://unused/kalshi"
        ));
        List<MarketMetadataReadRequest> requests = new ArrayList<>();

        FrontendMarketMetadataCatalog catalog = FrontendAdapterMain.loadMarketMetadata(
            config,
            () -> request -> {
                requests.add(request);
                return List.of(metadata("MKT-1", "open"));
            }
        );

        assertEquals(1, requests.size());
        assertEquals(config.metadataMaxRows(), requests.get(0).maxRows());
        assertEquals(FrontendMarketMetadataCatalog.LoadStatus.LOADED, catalog.loadStatus());
        assertEquals(1, catalog.size());
    }

    private static FeatureOutput output(long eventTsMs, String sourceEventId) {
        return new FeatureOutput(
            "feature.bbo",
            "feature.bbo",
            "MKT-1",
            eventTsMs,
            sourceEventId,
            Map.of("midpoint_micros", eventTsMs)
        );
    }

    private static FeatureOutputRow row(String eventId, String createdAt, long eventTsMs, String sourceEventId) {
        return new FeatureOutputRow(eventId, Instant.parse(createdAt), output(eventTsMs, sourceEventId));
    }

    private static MarketMetadata metadata(String ticker, String status) {
        return new MarketMetadata(
            ticker,
            "EVENT-1",
            "SERIES-1",
            status,
            null,
            null,
            null,
            null,
            "{\"ticker\":\"" + ticker + "\"}"
        );
    }
}
