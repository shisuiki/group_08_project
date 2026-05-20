package edu.illinois.group8.frontend;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAdapterConfigTest {
    @Test
    void defaultSourceIsDb() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of());

        assertEquals(FrontendAdapterConfig.SourceMode.DB, config.sourceMode());
        assertEquals(FrontendAdapterConfig.FeatureSource.MODULES, config.featureSource());
        assertEquals(FrontendAdapterConfig.MetadataSource.AUTO, config.metadataSource());
        assertEquals(10_000, config.featureOutputMaxRows());
        assertFalse(config.featureOutputRefreshEnabled());
        assertEquals(1_000, config.featureOutputRefreshIntervalMs());
        assertEquals(10_000, config.featureOutputRefreshMaxRows());
        assertEquals(1_000, config.metadataMaxRows());
        assertFalse(config.includeSmokeMarkets());
        assertEquals(Path.of("frontend/tradingview-lightweight"), config.staticRoot());
    }

    @Test
    void releaseInfoReadsDeploymentEnvironment() {
        FrontendReleaseInfo info = FrontendReleaseInfo.from(Map.of(
            "KALSHI_RELEASE_SHA", "abcdef123456",
            "KALSHI_APP_IMAGE", "kalshi-project:abcdef",
            "KALSHI_DEPLOY_PROFILE", "live-product",
            "KALSHI_GITHUB_RUN_ID", "261",
            "KALSHI_GITHUB_RUN_ATTEMPT", "2"
        ));

        assertEquals("abcdef123456", info.sha());
        assertEquals("kalshi-project:abcdef", info.image());
        assertEquals("live-product", info.profile());
        assertEquals("261", info.runId());
        assertEquals("2", info.runAttempt());
    }

    @Test
    void dbSourceAliasesParse() {
        for (String alias : new String[] {"db", "postgres", "postgresql", "timescale", "timescaledb"}) {
            assertEquals(FrontendAdapterConfig.SourceMode.DB, FrontendAdapterConfig.SourceMode.parse(alias));
        }
    }

    @Test
    void blankSourceAliasUsesDefaultDb() {
        assertEquals(FrontendAdapterConfig.SourceMode.DB, FrontendAdapterConfig.SourceMode.parse(null));
        assertEquals(FrontendAdapterConfig.SourceMode.DB, FrontendAdapterConfig.SourceMode.parse(" "));
    }

    @Test
    void explicitRecordingAliasesParse() {
        for (String alias : new String[] {"recording", "history", "storage"}) {
            assertEquals(FrontendAdapterConfig.SourceMode.RECORDING, FrontendAdapterConfig.SourceMode.parse(alias));
        }
    }

    @Test
    void featureSourceAliasesParse() {
        assertEquals(FrontendAdapterConfig.FeatureSource.MODULES, FrontendAdapterConfig.FeatureSource.parse(null));
        assertEquals(FrontendAdapterConfig.FeatureSource.MODULES, FrontendAdapterConfig.FeatureSource.parse("modules"));
        assertEquals(
            FrontendAdapterConfig.FeatureSource.MODULES,
            FrontendAdapterConfig.FeatureSource.parse("feature-modules")
        );
        assertEquals(
            FrontendAdapterConfig.FeatureSource.FEATURE_OUTPUTS,
            FrontendAdapterConfig.FeatureSource.parse("feature_outputs")
        );
        assertEquals(
            FrontendAdapterConfig.FeatureSource.FEATURE_OUTPUTS,
            FrontendAdapterConfig.FeatureSource.parse("db-features")
        );
        assertEquals(
            FrontendAdapterConfig.FeatureSource.FEATURE_OUTPUTS,
            FrontendAdapterConfig.FeatureSource.parse("persisted")
        );
        assertEquals(
            FrontendAdapterConfig.FeatureSource.LATEST_MARKET_STATE,
            FrontendAdapterConfig.FeatureSource.parse("latest-state")
        );
        assertEquals(
            FrontendAdapterConfig.FeatureSource.LATEST_MARKET_STATE,
            FrontendAdapterConfig.FeatureSource.parse("latest_market_state")
        );
    }

    @Test
    void metadataSourceAliasesParse() {
        assertEquals(FrontendAdapterConfig.MetadataSource.AUTO, FrontendAdapterConfig.MetadataSource.parse(null));
        assertEquals(FrontendAdapterConfig.MetadataSource.AUTO, FrontendAdapterConfig.MetadataSource.parse("auto"));
        assertEquals(FrontendAdapterConfig.MetadataSource.DB, FrontendAdapterConfig.MetadataSource.parse("db"));
        assertEquals(
            FrontendAdapterConfig.MetadataSource.DB,
            FrontendAdapterConfig.MetadataSource.parse("market-metadata")
        );
        assertEquals(FrontendAdapterConfig.MetadataSource.DISABLED, FrontendAdapterConfig.MetadataSource.parse("off"));
        assertEquals(
            FrontendAdapterConfig.MetadataSource.DISABLED,
            FrontendAdapterConfig.MetadataSource.parse("disabled")
        );
    }

    @Test
    void invalidFeatureSourceAndMaxRowsAreRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_FEATURE_SOURCE", "kafka"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS", "0"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS", "0"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS", "0"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_METADATA_SOURCE", "kafka"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FrontendAdapterConfig.from(Map.of("FRONTEND_ADAPTER_METADATA_MAX_ROWS", "0"))
        );
    }

    @Test
    void dbConfigFallsBackToWriterEnv() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://db/kalshi",
            "DB_WRITER_DATABASE_USER", "writer",
            "DB_WRITER_DATABASE_PASSWORD", "secret"
        ));

        assertEquals("jdbc:postgresql://db/kalshi", config.dbUrl());
        assertEquals("writer", config.dbUser());
        assertEquals("secret", config.dbPassword());
        assertFalse(config.dbIncludeReplayEvents());
        assertEquals("", config.dbReplayId());
    }

    @Test
    void frontendDbConfigOverridesWriterEnv() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_DB_URL", "jdbc:postgresql://frontend/kalshi",
            "FRONTEND_ADAPTER_DB_USER", "frontend",
            "FRONTEND_ADAPTER_DB_PASSWORD", "frontend-secret",
            "FRONTEND_ADAPTER_DB_INCLUDE_REPLAY", "true",
            "FRONTEND_ADAPTER_DB_REPLAY_ID", "replay-1",
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://writer/kalshi",
            "DB_WRITER_DATABASE_USER", "writer",
            "DB_WRITER_DATABASE_PASSWORD", "writer-secret"
        ));

        assertEquals("jdbc:postgresql://frontend/kalshi", config.dbUrl());
        assertEquals("frontend", config.dbUser());
        assertEquals("frontend-secret", config.dbPassword());
        assertTrue(config.dbIncludeReplayEvents());
        assertEquals("replay-1", config.dbReplayId());
    }

    @Test
    void featureOutputModeParsesMaxRows() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS", "250"
        ));

        assertEquals(FrontendAdapterConfig.FeatureSource.FEATURE_OUTPUTS, config.featureSource());
        assertEquals(250, config.featureOutputMaxRows());
        assertTrue(config.featureOutputRefreshEnabled());
        assertEquals(1_000, config.featureOutputRefreshIntervalMs());
        assertEquals(250, config.featureOutputRefreshMaxRows());
    }

    @Test
    void latestMarketStateModeDefaultsToDbRefresh() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "latest-state",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS", "250"
        ));

        assertEquals(FrontendAdapterConfig.FeatureSource.LATEST_MARKET_STATE, config.featureSource());
        assertEquals(250, config.featureOutputMaxRows());
        assertTrue(config.featureOutputRefreshEnabled());
        assertEquals(250, config.featureOutputRefreshIntervalMs());
        assertEquals(250, config.featureOutputRefreshMaxRows());
    }

    @Test
    void featureOutputRefreshConfigCanBeOverridden() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_FEATURE_SOURCE", "feature_outputs",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_ENABLED", "false",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS", "2500",
            "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS", "75"
        ));

        assertFalse(config.featureOutputRefreshEnabled());
        assertEquals(2500, config.featureOutputRefreshIntervalMs());
        assertEquals(75, config.featureOutputRefreshMaxRows());
    }

    @Test
    void metadataConfigParsesSourceAndMaxRows() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_METADATA_SOURCE", "db",
            "FRONTEND_ADAPTER_METADATA_MAX_ROWS", "250",
            "FRONTEND_ADAPTER_INCLUDE_SMOKE_MARKETS", "true"
        ));

        assertEquals(FrontendAdapterConfig.MetadataSource.DB, config.metadataSource());
        assertEquals(250, config.metadataMaxRows());
        assertTrue(config.includeSmokeMarkets());
    }

    @Test
    void staticRootCanBeOverridden() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of(
            "FRONTEND_ADAPTER_STATIC_ROOT", "/app/frontend/tradingview-lightweight"
        ));

        assertEquals(Path.of("/app/frontend/tradingview-lightweight"), config.staticRoot());
    }
}
