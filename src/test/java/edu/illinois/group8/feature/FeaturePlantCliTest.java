package edu.illinois.group8.feature;

import edu.illinois.group8.metrics.BackendMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturePlantCliTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultDbSourceRequiresDatabaseUrl() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.main(new String[] {"--db-url="})
        );

        assertTrue(thrown.getMessage().contains("FEATUREPLANT_DB_URL"));
    }

    @Test
    void explicitRecordingSourceAliasesDoNotRequireDatabaseUrl() {
        for (String source : List.of("recording", "history", "storage")) {
            FeaturePlantCli.main(new String[] {
                "--source=" + source,
                "--db-url=",
                "--output=stdout",
                "--root=" + tempDir,
                "--max-events=10",
                "--run-once"
            });
        }
    }

    @Test
    void dbOutputRequiresDatabaseUrlEvenForRecordingSource() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.main(new String[] {
                "--source=recording",
                "--root=" + tempDir,
                "--output=db",
                "--db-url=",
                "--max-events=0",
                "--run-once"
            })
        );

        assertTrue(thrown.getMessage().contains("FEATUREPLANT_DB_URL"));
        assertTrue(thrown.getMessage().contains("FEATUREPLANT_OUTPUT"));
    }

    @Test
    void dbOutputWithDatabaseUrlCanRunEmptyRecordingSource() {
        FeaturePlantCli.main(new String[] {
            "--source=recording",
            "--root=" + tempDir,
            "--output=db",
            "--db-url=jdbc:postgresql://db:5432/kalshi",
            "--max-events=0",
            "--run-once"
        });
    }

    @Test
    void asyncDbOutputWithDatabaseUrlCanRunEmptyRecordingSource() {
        FeaturePlantCli.main(new String[] {
            "--source=recording",
            "--root=" + tempDir,
            "--output=db",
            "--db-url=jdbc:postgresql://db:5432/kalshi",
            "--db-output-async",
            "--db-output-queue-capacity=7",
            "--db-output-batch-size=3",
            "--db-output-close-timeout-ms=10",
            "--max-events=0",
            "--run-once"
        });
    }

    @Test
    void combinedOutputWithDatabaseUrlCanRunEmptyRecordingSource() {
        FeaturePlantCli.main(new String[] {
            "--source=recording",
            "--root=" + tempDir,
            "--output=stdout,db",
            "--db-url=jdbc:postgresql://db:5432/kalshi",
            "--max-events=0",
            "--run-once"
        });
    }

    @Test
    void dbCursorNameFlagIsAcceptedWithoutAffectingNonDbSources() {
        FeaturePlantCli.main(new String[] {
            "--source=recording",
            "--root=" + tempDir,
            "--output=stdout",
            "--db-url=",
            "--db-cursor-name=featureplant-prod",
            "--max-events=0",
            "--run-once"
        });
    }

    @Test
    void unsupportedOutputModeIsRejected() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.main(new String[] {
                "--source=recording",
                "--root=" + tempDir,
                "--output=kafka",
                "--max-events=0",
                "--run-once"
            })
        );

        assertTrue(thrown.getMessage().contains("FEATUREPLANT_OUTPUT"));
    }

    @Test
    void defaultOutputRemainsStdoutAndDoesNotCreateDbSink() {
        RecordingDbSinkFactory factory = new RecordingDbSinkFactory();

        FeatureOutputSink sink = FeaturePlantCli.Config.from(Map.of())
            .outputSink(new BackendMetrics(), factory);

        assertTrue(sink instanceof StdoutFeatureOutputSink);
        assertTrue(factory.calls.isEmpty());
    }

    @Test
    void dbOutputUsesWriterUrlFallbackAndSyncDefault() {
        RecordingDbSinkFactory factory = new RecordingDbSinkFactory();

        FeaturePlantCli.Config.from(Map.of(
            "FEATUREPLANT_OUTPUT", "db",
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://writer/kalshi",
            "DB_WRITER_DATABASE_USER", "writer",
            "DB_WRITER_DATABASE_PASSWORD", "secret"
        )).outputSink(new BackendMetrics(), factory);

        assertTrue(factory.calls.size() == 1);
        DbSinkCall call = factory.calls.get(0);
        assertTrue(call.dbUrl().equals("jdbc:postgresql://writer/kalshi"));
        assertTrue(call.dbUser().equals("writer"));
        assertTrue(call.dbPassword().equals("secret"));
        assertTrue(!call.asyncEnabled());
    }

    @Test
    void asyncDbOutputParsesExplicitSizing() {
        RecordingDbSinkFactory factory = new RecordingDbSinkFactory();

        FeaturePlantCli.Config.from(Map.of(
            "FEATUREPLANT_OUTPUT", "db",
            "FEATUREPLANT_DB_URL", "jdbc:postgresql://db/kalshi",
            "FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED", "true",
            "FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY", "7",
            "FEATUREPLANT_DB_OUTPUT_BATCH_SIZE", "3",
            "FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS", "11"
        )).outputSink(new BackendMetrics(), factory);

        DbSinkCall call = factory.calls.get(0);
        assertTrue(call.asyncEnabled());
        assertTrue(call.queueCapacity() == 7);
        assertTrue(call.batchSize() == 3);
        assertTrue(call.closeTimeoutMs() == 11L);
    }

    @Test
    void asyncDbOutputSizingFallsBackToDbWriterSizing() {
        RecordingDbSinkFactory factory = new RecordingDbSinkFactory();

        FeaturePlantCli.Config.from(Map.of(
            "FEATUREPLANT_OUTPUT", "db",
            "FEATUREPLANT_DB_URL", "jdbc:postgresql://db/kalshi",
            "FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED", "true",
            "DB_WRITER_QUEUE_CAPACITY", "11",
            "DB_WRITER_BATCH_SIZE", "5"
        )).outputSink(new BackendMetrics(), factory);

        DbSinkCall call = factory.calls.get(0);
        assertTrue(call.asyncEnabled());
        assertTrue(call.queueCapacity() == 11);
        assertTrue(call.batchSize() == 5);
    }

    @Test
    void bothOutputCreatesOneDbSink() {
        RecordingDbSinkFactory factory = new RecordingDbSinkFactory();

        FeatureOutputSink sink = FeaturePlantCli.Config.from(Map.of(
            "FEATUREPLANT_OUTPUT", "both",
            "FEATUREPLANT_DB_URL", "jdbc:postgresql://db/kalshi"
        )).outputSink(new BackendMetrics(), factory);

        assertTrue(sink instanceof CompositeFeatureOutputSink);
        assertTrue(factory.calls.size() == 1);
    }

    @Test
    void invalidAsyncDbOutputConfigIsRejected() {
        IllegalArgumentException invalidBoolean = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.Config.from(Map.of("FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED", "sometimes"))
        );
        assertTrue(invalidBoolean.getMessage().contains("FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED"));

        IllegalArgumentException invalidQueue = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.Config.from(Map.of("FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY", "0"))
        );
        assertTrue(invalidQueue.getMessage().contains("FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY"));

        IllegalArgumentException invalidBatch = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.Config.from(Map.of("FEATUREPLANT_DB_OUTPUT_BATCH_SIZE", "-1"))
        );
        assertTrue(invalidBatch.getMessage().contains("FEATUREPLANT_DB_OUTPUT_BATCH_SIZE"));

        IllegalArgumentException invalidCloseTimeout = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.Config.from(Map.of("FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS", "-1"))
        );
        assertTrue(invalidCloseTimeout.getMessage().contains("FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS"));
    }

    private record DbSinkCall(
        String dbUrl,
        String dbUser,
        String dbPassword,
        boolean asyncEnabled,
        int queueCapacity,
        int batchSize,
        long closeTimeoutMs
    ) {
    }

    private static final class RecordingDbSinkFactory implements FeaturePlantCli.DbOutputSinkFactory {
        private final List<DbSinkCall> calls = new ArrayList<>();

        @Override
        public FeatureOutputSink create(
            String dbUrl,
            String dbUser,
            String dbPassword,
            boolean asyncEnabled,
            int queueCapacity,
            int batchSize,
            long closeTimeoutMs,
            BackendMetrics metrics
        ) {
            calls.add(new DbSinkCall(
                dbUrl,
                dbUser,
                dbPassword,
                asyncEnabled,
                queueCapacity,
                batchSize,
                closeTimeoutMs
            ));
            return new NoopFeatureOutputSink();
        }
    }

    private static final class NoopFeatureOutputSink implements FeatureOutputSink {
        @Override
        public void write(FeatureOutput output) {
        }
    }
}
