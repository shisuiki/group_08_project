package edu.illinois.group8.frontend;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAdapterConfigTest {
    @Test
    void defaultSourceIsDb() {
        FrontendAdapterConfig config = FrontendAdapterConfig.from(Map.of());

        assertEquals(FrontendAdapterConfig.SourceMode.DB, config.sourceMode());
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
}
