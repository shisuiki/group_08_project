package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.CanonicalEnvelopeSource;
import edu.illinois.group8.feature.DbCanonicalEnvelopeSource;
import edu.illinois.group8.feature.RecordingCanonicalEnvelopeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

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
}
