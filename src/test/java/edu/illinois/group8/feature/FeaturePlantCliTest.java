package edu.illinois.group8.feature;

import edu.illinois.group8.metrics.BackendMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void noopOutputModeDoesNotCreateDbSink() {
        RecordingDbSinkFactory factory = new RecordingDbSinkFactory();

        FeatureOutputSink sink = FeaturePlantCli.Config.from(Map.of(
            "FEATUREPLANT_SOURCE", "aeron",
            "FEATUREPLANT_OUTPUT", "none",
            "FEATUREPLANT_DB_URL", ""
        )).outputSink(new BackendMetrics(), factory);

        assertEquals("edu.illinois.group8.feature.NoopFeatureOutputSink", sink.getClass().getName());
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
    void dbSourceDbOutputWithCursorUsesTransactionalProjectorEvenWhenAsyncEnabled() {
        RecordingDbProjectorFactory factory = new RecordingDbProjectorFactory();
        BackendMetrics metrics = new BackendMetrics();

        FeaturePlantCli.Config config = FeaturePlantCli.Config.from(Map.of(
            "FEATUREPLANT_SOURCE", "db",
            "FEATUREPLANT_OUTPUT", "db",
            "FEATUREPLANT_DB_URL", "jdbc:postgresql://db/kalshi",
            "FEATUREPLANT_DB_USER", "writer",
            "FEATUREPLANT_DB_PASSWORD", "secret",
            "FEATUREPLANT_DB_CURSOR_NAME", "featureplant-prod",
            "FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED", "true",
            "FEATUREPLANT_BATCH_SIZE", "17"
        ));

        assertTrue(config.usesTransactionalDbProjector());

        FeaturePlantDbProjector projector = config.dbProjector(metrics, factory);

        projector.close();

        assertEquals(1, factory.calls.size());
        DbProjectorCall call = factory.calls.get(0);
        assertEquals("jdbc:postgresql://db/kalshi", call.dbUrl());
        assertEquals("writer", call.dbUser());
        assertEquals("secret", call.dbPassword());
        assertEquals("featureplant-prod", call.cursorName());
        assertSame(metrics, call.metrics());
    }

    @Test
    void postgresAliasesCanUseTransactionalProjector() {
        FeaturePlantCli.Config config = FeaturePlantCli.Config.from(Map.of(
            "FEATUREPLANT_SOURCE", "postgres",
            "FEATUREPLANT_OUTPUT", "postgres",
            "FEATUREPLANT_DB_URL", "jdbc:postgresql://db/kalshi",
            "FEATUREPLANT_DB_CURSOR_NAME", "featureplant-prod"
        ));

        assertTrue(config.usesTransactionalDbProjector());
    }

    @Test
    void durableCursorRejectsAsyncDbSinkOutsideTransactionalProjectorMode() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.Config.from(Map.of(
                "FEATUREPLANT_SOURCE", "db",
                "FEATUREPLANT_OUTPUT", "both",
                "FEATUREPLANT_DB_URL", "jdbc:postgresql://db/kalshi",
                "FEATUREPLANT_DB_CURSOR_NAME", "featureplant-prod",
                "FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED", "true"
            )).outputSink(new BackendMetrics(), new RecordingDbSinkFactory())
        );

        assertTrue(thrown.getMessage().contains("FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED"));
        assertTrue(thrown.getMessage().contains("durable DB cursor"));
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

    @Test
    void metricsServerFactoryNoopsWhenDisabledByDefault() {
        AtomicInteger factoryCalls = new AtomicInteger();

        FeaturePlantCli.MetricsServerHandle server = FeaturePlantCli.Config.from(Map.of())
            .metricsServer(new BackendMetrics(), (host, port, metrics) -> {
                factoryCalls.incrementAndGet();
                return () -> {
                };
            });

        server.close();

        assertEquals(0, factoryCalls.get());
    }

    @Test
    void metricsServerFactoryUsesConfiguredEndpointAndSharedMetrics() {
        BackendMetrics sharedMetrics = new BackendMetrics();
        AtomicReference<String> capturedHost = new AtomicReference<>();
        AtomicInteger capturedPort = new AtomicInteger();
        AtomicReference<BackendMetrics> capturedMetrics = new AtomicReference<>();
        AtomicInteger closeCalls = new AtomicInteger();

        FeaturePlantCli.MetricsServerHandle server = FeaturePlantCli.Config.from(Map.of(
            "FEATUREPLANT_METRICS_HOST", "127.0.0.1",
            "FEATUREPLANT_METRICS_PORT", "19094"
        )).metricsServer(sharedMetrics, (host, port, metrics) -> {
            capturedHost.set(host);
            capturedPort.set(port);
            capturedMetrics.set(metrics);
            return closeCalls::incrementAndGet;
        });

        assertEquals("127.0.0.1", capturedHost.get());
        assertEquals(19094, capturedPort.get());
        assertSame(sharedMetrics, capturedMetrics.get());

        server.close();

        assertEquals(1, closeCalls.get());
    }

    @Test
    void metricsConfigParsesArgsAndRejectsInvalidValues() {
        FeaturePlantCli.Config config = FeaturePlantCli.Config.from(Map.of())
            .withArgs(new String[] {"--metrics-host=127.0.0.1", "--metrics-port=19095"});

        assertEquals("127.0.0.1", config.metricsHost());
        assertEquals(19095, config.metricsPort());

        IllegalArgumentException invalidPort = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.Config.from(Map.of("FEATUREPLANT_METRICS_PORT", "-1"))
        );
        assertTrue(invalidPort.getMessage().contains("FEATUREPLANT_METRICS_PORT"));

        IllegalArgumentException invalidHost = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.Config.from(Map.of("FEATUREPLANT_METRICS_PORT", "8094"))
                .withArgs(new String[] {"--metrics-host="})
                .metricsServer(new BackendMetrics(), (host, port, metrics) -> () -> {
                })
        );
        assertTrue(invalidHost.getMessage().contains("FEATUREPLANT_METRICS_HOST"));
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

    private record DbProjectorCall(
        String dbUrl,
        String dbUser,
        String dbPassword,
        String cursorName,
        BackendMetrics metrics
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

    private static final class RecordingDbProjectorFactory implements FeaturePlantCli.DbProjectorFactory {
        private final List<DbProjectorCall> calls = new ArrayList<>();

        @Override
        public FeaturePlantDbProjector create(
            String dbUrl,
            String dbUser,
            String dbPassword,
            List<edu.illinois.group8.canonical.StreamContract> streams,
            List<FeatureModule> modules,
            long maxEvents,
            boolean includeReplayEvents,
            String replayId,
            String cursorName,
            BackendMetrics metrics
        ) {
            calls.add(new DbProjectorCall(dbUrl, dbUser, dbPassword, cursorName, metrics));
            return new FeaturePlantDbProjector(
                request -> List.of(),
                new NoopProjectionStore(),
                streams,
                modules,
                maxEvents,
                includeReplayEvents,
                replayId,
                cursorName,
                metrics
            );
        }
    }

    private static final class NoopProjectionStore implements edu.illinois.group8.storage.db.FeatureOutputProjectionStore {
        @Override
        public java.util.Optional<edu.illinois.group8.storage.db.CanonicalDbCursor> loadCursor(String cursorName) {
            return java.util.Optional.empty();
        }

        @Override
        public void commitProjection(
            String cursorName,
            edu.illinois.group8.storage.db.CanonicalDbCursor cursor,
            List<edu.illinois.group8.storage.db.FeatureOutputDbEvent> outputs,
            List<edu.illinois.group8.storage.db.LatestMarketState> latestStates
        ) {
        }
    }
}
