package edu.illinois.group8.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.feature.DbCanonicalEnvelopeSource;
import edu.illinois.group8.feature.RecordingCanonicalEnvelopeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchExportCliTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void defaultSourceIsDb() {
        ResearchExportCli.Config config = parse();

        assertEquals(ResearchExportCli.SourceMode.DB, config.sourceMode());
    }

    @Test
    void dbSourceAliasesParse() {
        for (String source : new String[] {"db", "postgres", "postgresql", "timescale", "timescaledb"}) {
            ResearchExportCli.Config config = parse("--source=" + source);

            assertEquals(ResearchExportCli.SourceMode.DB, config.sourceMode());
        }
    }

    @Test
    void dbMissingUrlFailsFast() {
        ResearchExportCli.Config config = parse("--db-url=");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, config::source);

        assertTrue(thrown.getMessage().contains("RESEARCH_EXPORT_DB_URL"));
        assertTrue(thrown.getMessage().contains("DB_WRITER_DATABASE_URL"));
        assertTrue(thrown.getMessage().contains("--db-url"));
    }

    @Test
    void researchDbEnvOverridesWriterEnv() {
        ResearchExportCli.Config config = parseWithEnv(Map.of(
            "RESEARCH_EXPORT_DB_URL", "jdbc:postgresql://research/kalshi",
            "RESEARCH_EXPORT_DB_USER", "research",
            "RESEARCH_EXPORT_DB_PASSWORD", "research-secret",
            "RESEARCH_EXPORT_DB_INCLUDE_REPLAY", "true",
            "RESEARCH_EXPORT_DB_REPLAY_ID", "replay-1",
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://writer/kalshi",
            "DB_WRITER_DATABASE_USER", "writer",
            "DB_WRITER_DATABASE_PASSWORD", "writer-secret"
        ));

        assertEquals("jdbc:postgresql://research/kalshi", config.dbUrl());
        assertEquals("research", config.dbUser());
        assertEquals("research-secret", config.dbPassword());
        assertTrue(config.dbIncludeReplayEvents());
        assertEquals("replay-1", config.dbReplayId());
    }

    @Test
    void dbEnvFallsBackToWriterEnv() {
        ResearchExportCli.Config config = parseWithEnv(Map.of(
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://writer/kalshi",
            "DB_WRITER_DATABASE_USER", "writer",
            "DB_WRITER_DATABASE_PASSWORD", "writer-secret"
        ));

        assertEquals("jdbc:postgresql://writer/kalshi", config.dbUrl());
        assertEquals("writer", config.dbUser());
        assertEquals("writer-secret", config.dbPassword());
    }

    @Test
    void dbArgsOverrideEnvAndBuildWithoutOpeningConnection() {
        ResearchExportCli.Config config = parseWithEnv(Map.of(
            "RESEARCH_EXPORT_DB_URL", "jdbc:postgresql://env/kalshi",
            "RESEARCH_EXPORT_DB_USER", "env-user",
            "RESEARCH_EXPORT_DB_PASSWORD", "env-secret"
        ),
            "--db-url=jdbc:postgresql://arg/kalshi",
            "--db-user=arg-user",
            "--db-password=arg-secret",
            "--include-replay",
            "--replay-id=replay-arg"
        );

        assertEquals("jdbc:postgresql://arg/kalshi", config.dbUrl());
        assertEquals("arg-user", config.dbUser());
        assertEquals("arg-secret", config.dbPassword());
        assertTrue(config.dbIncludeReplayEvents());
        assertEquals("replay-arg", config.dbReplayId());
        assertInstanceOf(DbCanonicalEnvelopeSource.class, config.source());
    }

    @Test
    void explicitRecordingSourceRunsWithoutDatabaseUrl() {
        Path output = tempDir.resolve("recording-output");
        ResearchExportCli.Config config = parse(
            "--source=recording",
            "--db-url=",
            "--root=" + tempDir.resolve("recordings"),
            "--output=" + output
        );

        assertInstanceOf(RecordingCanonicalEnvelopeSource.class, config.source());

        ResearchExportCli.run(config);

        assertTrue(Files.exists(output.resolve("metadata.json")));
    }

    @Test
    void dbMetadataDoesNotIncludePasswordOrUrl() throws IOException {
        Path output = tempDir.resolve("db-output");
        ResearchExportCli.Config config = parse(
            "--db-url=jdbc:postgresql://user:password@example.com/kalshi",
            "--db-user=user",
            "--db-password=super-secret",
            "--include-replay",
            "--replay-id=replay-1",
            "--output=" + output
        );
        Files.createDirectories(output);

        ResearchExportCli.writeMetadata(config, 0L, Map.of());

        String raw = Files.readString(output.resolve("metadata.json"));
        JsonNode metadata = MAPPER.readTree(raw);
        assertEquals("db", metadata.path("source_mode").asText());
        assertTrue(metadata.path("source_root").isNull());
        assertTrue(metadata.path("source_db_url_configured").asBoolean());
        assertTrue(metadata.path("source_db_user_configured").asBoolean());
        assertTrue(metadata.path("db_include_replay_events").asBoolean());
        assertEquals("replay-1", metadata.path("db_replay_id").asText());
        assertFalse(raw.contains("super-secret"));
        assertFalse(raw.contains("jdbc:postgresql"));
        assertFalse(raw.contains("user:password"));
    }

    private ResearchExportCli.Config parse(String... extraArgs) {
        return parseWithEnv(Map.of(), extraArgs);
    }

    private ResearchExportCli.Config parseWithEnv(Map<String, String> env, String... extraArgs) {
        String[] args = new String[extraArgs.length + 3];
        args[0] = "--streams=canonical.trade,canonical.ticker,derived.top_of_book";
        args[1] = "--modules=bbo,ticker_snapshot,trade_tape";
        args[2] = "--output=" + tempDir.resolve("export-" + extraArgs.length + "-" + System.nanoTime());
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        return ResearchExportCli.Config.parse(args, env);
    }
}
