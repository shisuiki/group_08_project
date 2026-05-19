package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.CanonicalEnvelopeSource;
import edu.illinois.group8.feature.DbCanonicalEnvelopeSource;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.feature.RecordingCanonicalEnvelopeSource;
import edu.illinois.group8.storage.db.FeatureOutputReadRequest;
import edu.illinois.group8.storage.db.JdbcMarketMetadataReader;
import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.MarketMetadataReadRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
