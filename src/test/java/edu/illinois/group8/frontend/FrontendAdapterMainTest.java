package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.CanonicalEnvelopeSource;
import edu.illinois.group8.feature.DbCanonicalEnvelopeSource;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.feature.RecordingCanonicalEnvelopeSource;
import edu.illinois.group8.storage.db.FeatureOutputReadRequest;
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
}
