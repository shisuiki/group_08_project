package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveProductSmokeDbProbeCliTest {
    @Test
    void configPrefersSmokeDbAndArgsOverride() {
        LiveProductSmokeDbProbeCli.Config config = LiveProductSmokeDbProbeCli.Config.from(
            new String[] {
                "cursorCommitSeq",
                "--cursor-name=live-cursor",
                "--db-url=jdbc:postgresql://cli/live",
                "--db-user=cli-user",
                "--db-password=cli-password"
            },
            Map.of(
                "LIVE_PRODUCT_SMOKE_DB_URL", "jdbc:postgresql://smoke/live",
                "DB_WRITER_DATABASE_URL", "jdbc:postgresql://writer/live",
                "LIVE_PRODUCT_SMOKE_DB_USER", "smoke-user",
                "LIVE_PRODUCT_SMOKE_DB_PASSWORD", "smoke-password"
            )
        );

        assertEquals("cursorCommitSeq", config.command());
        assertEquals("jdbc:postgresql://cli/live", config.dbUrl());
        assertEquals("cli-user", config.dbUser());
        assertEquals("cli-password", config.dbPassword());
        assertEquals("live-cursor", config.options().get("cursor-name"));
    }

    @Test
    void failsWhenDbUrlMissing() {
        Output output = new Output();

        int exitCode = LiveProductSmokeDbProbeCli.run(
            new String[] {"maxCanonicalCommitSeq"},
            Map.of(),
            output.out(),
            output.err()
        );

        assertEquals(2, exitCode);
        assertTrue(output.stderr().contains("DB URL is empty"));
    }

    @Test
    void probeSeedWritesMetadataAndCanonicalEvents() {
        RecordingJdbc jdbc = new RecordingJdbc();
        LiveProductSmokeDbProbe probe = new LiveProductSmokeDbProbe(
            jdbc::openConnection,
            new edu.illinois.group8.canonical.JsonCanonicalSerializer().mapper(),
            () -> 1_700_000_000_000L
        );

        LiveProductSmokeDbProbe.SeedResult result = probe.seedCanonicalEvents(
            "run-1",
            "MKT-1",
            "live-product-smoke-run-1"
        );

        assertEquals(3, result.seededCount());
        assertEquals(103, result.targetCommitSeq());
        assertEquals(1, jdbc.metadataUpserts);
        assertEquals(3, jdbc.canonicalInserts.size());
        Map<Integer, Object> bbo = jdbc.canonicalInserts.get(0);
        assertEquals("live-product-smoke-run-1-bbo-001", bbo.get(1));
        assertEquals("derived.top_of_book", bbo.get(4));
        assertEquals("top_of_book_update", bbo.get(5));
        assertEquals("MKT-1", bbo.get(7));
        assertEquals(1_700_000_000_001L, bbo.get(8));
        assertTrue(String.valueOf(bbo.get(11)).contains("\"midpoint_micros\"") == false);
        assertTrue(String.valueOf(bbo.get(11)).contains("\"bid_price_micros\":451000"));
        assertEquals(1, jdbc.commitCalls);
        assertEquals(0, jdbc.rollbackCalls);
    }

    @Test
    void probeScalarCommandsReadExpectedRows() {
        RecordingJdbc jdbc = new RecordingJdbc()
            .withScalar(LiveProductSmokeDbProbe.CURSOR_COMMIT_SEQ_SQL, 7L)
            .withScalar(LiveProductSmokeDbProbe.MAX_CANONICAL_COMMIT_SEQ_SQL, 11L)
            .withScalar(LiveProductSmokeDbProbe.FEATURE_OUTPUTS_FOR_PREFIX_SQL, 3L)
            .withScalar(LiveProductSmokeDbProbe.RECENT_NON_SMOKE_CANONICAL_EVENTS_SQL, 2L);
        LiveProductSmokeDbProbe probe = new LiveProductSmokeDbProbe(jdbc::openConnection);

        assertEquals(7L, probe.cursorCommitSeq("cursor"));
        assertEquals(11L, probe.maxCanonicalCommitSeq());
        LiveProductSmokeDbProbe.FeatureOutputProgress progress = probe.featureOutputsForPrefix("prefix", "cursor");
        assertEquals(3L, progress.featureOutputCount());
        assertEquals(7L, progress.cursorCommitSeq());
        assertEquals(2L, probe.recentNonSmokeCanonicalEvents());
    }

    @Test
    void probeLiveDataCommandsReadExpectedRows() {
        RecordingJdbc jdbc = new RecordingJdbc()
            .withRows(
                LiveProductSmokeDbProbe.LATEST_NON_SMOKE_CANONICAL_AFTER_SQL,
                List.<Object[]>of(new Object[] {"live-event-1", "MKT-1", "derived.top_of_book", 42L, 1_700_000_000_000L})
            )
            .withRows(
                LiveProductSmokeDbProbe.FEATURE_OUTPUTS_FOR_SOURCE_EVENT_SQL,
                List.<Object[]>of(new Object[] {1L, "feature.bbo", "MKT-1", 1_700_000_000_000L, "live-event-1"})
            )
            .withRows(
                LiveProductSmokeDbProbe.LATEST_NON_SMOKE_FEATURE_OUTPUT_AFTER_SQL,
                List.<Object[]>of(new Object[] {
                    "live-event-1", "feature.bbo", "MKT-1", 1_700_000_000_000L, "derived.top_of_book", 42L
                })
            );
        LiveProductSmokeDbProbe probe = new LiveProductSmokeDbProbe(jdbc::openConnection);

        LiveProductSmokeDbProbe.CanonicalEventSummary event = probe.latestNonSmokeCanonicalAfter(41L);
        assertEquals("live-event-1", event.eventId());
        assertEquals("MKT-1", event.marketTicker());
        assertEquals("derived.top_of_book", event.streamName());
        assertEquals(42L, event.commitSeq());
        assertEquals(1_700_000_000_000L, event.eventTsMs());

        LiveProductSmokeDbProbe.FeatureOutputSummary feature = probe.featureOutputsForSourceEvent("live-event-1");
        assertEquals(1L, feature.count());
        assertEquals("feature.bbo", feature.featureName());
        assertEquals("MKT-1", feature.marketTicker());
        assertEquals(1_700_000_000_000L, feature.eventTsMs());
        assertEquals("live-event-1", feature.sourceEventId());

        LiveProductSmokeDbProbe.LiveFeatureOutputSummary latest = probe.latestNonSmokeFeatureOutputAfter(41L);
        assertEquals("live-event-1", latest.sourceEventId());
        assertEquals("feature.bbo", latest.featureName());
        assertEquals("MKT-1", latest.marketTicker());
        assertEquals("derived.top_of_book", latest.streamName());
        assertEquals(42L, latest.commitSeq());
    }

    @Test
    void probeLatencyForSourceEventReadsExpectedRows() {
        RecordingJdbc jdbc = new RecordingJdbc()
            .withRows(
                LiveProductSmokeDbProbe.LATENCY_FOR_SOURCE_EVENT_SQL,
                List.<Object[]>of(new Object[] {
                    "live-event-1", 42L, 1_700_000_001_000L, 1_700_000_001_250L, 1_700_000_001_400L,
                    42L, 250L, 150L, 400L
                })
            );
        LiveProductSmokeDbProbe probe = new LiveProductSmokeDbProbe(jdbc::openConnection);

        LiveProductSmokeDbProbe.ProductLatencySummary latency = probe.latencyForSourceEvent("live-event-1");

        assertEquals("ok", latency.status());
        assertEquals("live-event-1", latency.sourceEventId());
        assertEquals(42L, latency.canonicalCommitSeq());
        assertEquals(1_700_000_001_000L, latency.canonicalCreatedAtMs());
        assertEquals(1_700_000_001_250L, latency.featureOutputCreatedAtMs());
        assertEquals(1_700_000_001_400L, latency.latestMarketStateUpdatedAtMs());
        assertEquals(42L, latency.latestMarketStateCommitSeq());
        assertEquals(250L, latency.canonicalToFeatureMs());
        assertEquals(150L, latency.featureToLatestStateMs());
        assertEquals(400L, latency.canonicalToLatestStateMs());
    }

    @Test
    void probeLatencyForSourceEventReportsMissingStates() {
        LiveProductSmokeDbProbe missingCanonical = new LiveProductSmokeDbProbe(new RecordingJdbc()::openConnection);
        LiveProductSmokeDbProbe.ProductLatencySummary missingCanonicalLatency =
            missingCanonical.latencyForSourceEvent("missing-event");
        assertEquals("missing_canonical_event", missingCanonicalLatency.status());
        assertEquals("missing-event", missingCanonicalLatency.sourceEventId());
        assertEquals(-1L, missingCanonicalLatency.canonicalToLatestStateMs());

        RecordingJdbc missingFeatureJdbc = new RecordingJdbc()
            .withRows(
                LiveProductSmokeDbProbe.LATENCY_FOR_SOURCE_EVENT_SQL,
                List.<Object[]>of(new Object[] {
                    "live-event-2", 43L, 1_700_000_002_000L, -1L, -1L, -1L, -1L, -1L, -1L
                })
            );
        LiveProductSmokeDbProbe.ProductLatencySummary missingFeature =
            new LiveProductSmokeDbProbe(missingFeatureJdbc::openConnection).latencyForSourceEvent("live-event-2");
        assertEquals("missing_feature_output", missingFeature.status());
        assertEquals(-1L, missingFeature.featureOutputCreatedAtMs());

        RecordingJdbc missingLatestStateJdbc = new RecordingJdbc()
            .withRows(
                LiveProductSmokeDbProbe.LATENCY_FOR_SOURCE_EVENT_SQL,
                List.<Object[]>of(new Object[] {
                    "live-event-3", 44L, 1_700_000_003_000L, 1_700_000_003_200L, -1L, -1L,
                    200L, -1L, -1L
                })
            );
        LiveProductSmokeDbProbe.ProductLatencySummary missingLatestState =
            new LiveProductSmokeDbProbe(missingLatestStateJdbc::openConnection).latencyForSourceEvent("live-event-3");
        assertEquals("missing_latest_market_state", missingLatestState.status());
        assertEquals(200L, missingLatestState.canonicalToFeatureMs());
        assertEquals(-1L, missingLatestState.featureToLatestStateMs());
    }

    @Test
    void latencySqlJoinsCanonicalFeatureAndLatestStateTimings() {
        String sql = LiveProductSmokeDbProbe.LATENCY_FOR_SOURCE_EVENT_SQL.toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("from canonical_events"));
        assertTrue(sql.contains("from feature_outputs"));
        assertTrue(sql.contains("from latest_market_state"));
        assertTrue(sql.contains("extract(epoch from created_at)"));
        assertTrue(sql.contains("extract(epoch from lms.updated_at)"));
        assertTrue(sql.contains("last_canonical_commit_seq"));
        assertTrue(sql.contains("greatest(0"));
    }

    @Test
    void probePipelineReliabilitySnapshotSummarizesBoundedProgress() {
        RecordingJdbc jdbc = new RecordingJdbc()
            .withRows(
                LiveProductSmokeDbProbe.RAW_PROGRESS_SQL,
                List.<Object[]>of(new Object[] {12L, 555L, 800L})
            )
            .withRows(
                LiveProductSmokeDbProbe.CANONICAL_PROGRESS_SQL,
                List.<Object[]>of(new Object[] {10L, 44L, 900L})
            )
            .withRows(
                LiveProductSmokeDbProbe.FEATURE_PROGRESS_SQL,
                List.<Object[]>of(new Object[] {9L, 1_700_000_000_000L, 1_000L})
            )
            .withScalar(LiveProductSmokeDbProbe.RAW_WITHOUT_CANONICAL_SQL, 2L)
            .withScalar(LiveProductSmokeDbProbe.CURSOR_COMMIT_SEQ_SQL, 44L);
        LiveProductSmokeDbProbe probe = new LiveProductSmokeDbProbe(jdbc::openConnection);

        LiveProductSmokeDbProbe.PipelineReliabilitySnapshot snapshot =
            probe.pipelineReliabilitySnapshot("cursor", 120L, 500);

        assertEquals("ok", snapshot.status());
        assertEquals(120L, snapshot.windowSeconds());
        assertEquals(500, snapshot.rowLimit());
        assertEquals(12L, snapshot.rawRecentCount());
        assertEquals(555L, snapshot.rawLatestReceiveTsNs());
        assertEquals(10L, snapshot.canonicalRecentCount());
        assertEquals(44L, snapshot.canonicalMaxCommitSeq());
        assertEquals(44L, snapshot.cursorCommitSeq());
        assertEquals(0L, snapshot.cursorLagEvents());
        assertEquals(9L, snapshot.featureRecentCount());
        assertEquals(2L, snapshot.rawWithoutCanonicalCount());
    }

    @Test
    void pipelineReliabilitySnapshotReportsDegradedWhenCursorLagsCanonical() {
        RecordingJdbc jdbc = new RecordingJdbc()
            .withRows(
                LiveProductSmokeDbProbe.RAW_PROGRESS_SQL,
                List.<Object[]>of(new Object[] {12L, 555L, 800L})
            )
            .withRows(
                LiveProductSmokeDbProbe.CANONICAL_PROGRESS_SQL,
                List.<Object[]>of(new Object[] {10L, 44L, 900L})
            )
            .withRows(
                LiveProductSmokeDbProbe.FEATURE_PROGRESS_SQL,
                List.<Object[]>of(new Object[] {9L, 1_700_000_000_000L, 1_000L})
            )
            .withScalar(LiveProductSmokeDbProbe.RAW_WITHOUT_CANONICAL_SQL, 2L)
            .withScalar(LiveProductSmokeDbProbe.CURSOR_COMMIT_SEQ_SQL, 41L);
        LiveProductSmokeDbProbe probe = new LiveProductSmokeDbProbe(jdbc::openConnection);

        LiveProductSmokeDbProbe.PipelineReliabilitySnapshot snapshot =
            probe.pipelineReliabilitySnapshot("cursor", 120L, 500);

        assertEquals("degraded", snapshot.status());
        assertEquals(3L, snapshot.cursorLagEvents());
    }

    @Test
    void cliPrintsSeedResultShape() {
        RecordingJdbc jdbc = new RecordingJdbc();
        Output output = new Output();
        LiveProductSmokeDbProbeCli.Config config = new LiveProductSmokeDbProbeCli.Config(
            "seedCanonicalEvents",
            "jdbc:postgresql://live/db",
            "user",
            "password",
            Map.of("run-id", "run", "market-ticker", "M", "prefix", "live-product-smoke-run"),
            false
        );

        int exitCode = LiveProductSmokeDbProbeCli.run(config, jdbc::openConnection, output.out(), output.err());

        assertEquals(0, exitCode);
        assertEquals("3|103\n", output.stdout());
    }

    @Test
    void cliPrintsLiveDataResultShapes() {
        RecordingJdbc jdbc = new RecordingJdbc()
            .withRows(
                LiveProductSmokeDbProbe.LATEST_NON_SMOKE_CANONICAL_AFTER_SQL,
                List.<Object[]>of(new Object[] {"live-event-1", "MKT-1", "derived.top_of_book", 42L, 1_700_000_000_000L})
            )
            .withRows(
                LiveProductSmokeDbProbe.FEATURE_OUTPUTS_FOR_SOURCE_EVENT_SQL,
                List.<Object[]>of(new Object[] {1L, "feature.bbo", "MKT-1", 1_700_000_000_000L, "live-event-1"})
            )
            .withRows(
                LiveProductSmokeDbProbe.LATEST_NON_SMOKE_FEATURE_OUTPUT_AFTER_SQL,
                List.<Object[]>of(new Object[] {
                    "live-event-1", "feature.bbo", "MKT-1", 1_700_000_000_000L, "derived.top_of_book", 42L
                })
            );
        Output output = new Output();
        LiveProductSmokeDbProbeCli.Config latestCanonical = new LiveProductSmokeDbProbeCli.Config(
            "latestNonSmokeCanonicalAfter",
            "jdbc:postgresql://live/db",
            "user",
            "password",
            Map.of("after-commit-seq", "41"),
            false
        );

        assertEquals(0, LiveProductSmokeDbProbeCli.run(latestCanonical, jdbc::openConnection, output.out(), output.err()));
        assertEquals("live-event-1|MKT-1|derived.top_of_book|42|1700000000000\n", output.stdout());

        Output featureOutput = new Output();
        LiveProductSmokeDbProbeCli.Config featureForSource = new LiveProductSmokeDbProbeCli.Config(
            "featureOutputsForSourceEvent",
            "jdbc:postgresql://live/db",
            "user",
            "password",
            Map.of("source-event-id", "live-event-1"),
            false
        );
        assertEquals(0, LiveProductSmokeDbProbeCli.run(
            featureForSource,
            jdbc::openConnection,
            featureOutput.out(),
            featureOutput.err()
        ));
        assertEquals("1|feature.bbo|MKT-1|1700000000000|live-event-1\n", featureOutput.stdout());

        Output latestFeature = new Output();
        LiveProductSmokeDbProbeCli.Config latestFeatureAfter = new LiveProductSmokeDbProbeCli.Config(
            "latestNonSmokeFeatureOutputAfter",
            "jdbc:postgresql://live/db",
            "user",
            "password",
            Map.of("after-commit-seq", "41"),
            false
        );
        assertEquals(0, LiveProductSmokeDbProbeCli.run(
            latestFeatureAfter,
            jdbc::openConnection,
            latestFeature.out(),
            latestFeature.err()
        ));
        assertEquals("live-event-1|feature.bbo|MKT-1|1700000000000|derived.top_of_book|42\n", latestFeature.stdout());
    }

    @Test
    void cliPrintsLatencyForSourceEventShape() {
        RecordingJdbc jdbc = new RecordingJdbc()
            .withRows(
                LiveProductSmokeDbProbe.LATENCY_FOR_SOURCE_EVENT_SQL,
                List.<Object[]>of(new Object[] {
                    "live-event-1", 42L, 1_700_000_001_000L, 1_700_000_001_250L, 1_700_000_001_400L,
                    42L, 250L, 150L, 400L
                })
            );
        Output output = new Output();
        LiveProductSmokeDbProbeCli.Config config = new LiveProductSmokeDbProbeCli.Config(
            "latencyForSourceEvent",
            "jdbc:postgresql://live/db",
            "user",
            "password",
            Map.of("source-event-id", "live-event-1"),
            false
        );

        assertEquals(0, LiveProductSmokeDbProbeCli.run(config, jdbc::openConnection, output.out(), output.err()));

        assertEquals(
            "ok|live-event-1|42|1700000001000|1700000001250|1700000001400|42|250|150|400\n",
            output.stdout()
        );
    }

    @Test
    void cliPrintsPipelineReliabilitySnapshotShape() {
        RecordingJdbc jdbc = new RecordingJdbc()
            .withRows(
                LiveProductSmokeDbProbe.RAW_PROGRESS_SQL,
                List.<Object[]>of(new Object[] {12L, 555L, 800L})
            )
            .withRows(
                LiveProductSmokeDbProbe.CANONICAL_PROGRESS_SQL,
                List.<Object[]>of(new Object[] {10L, 44L, 900L})
            )
            .withRows(
                LiveProductSmokeDbProbe.FEATURE_PROGRESS_SQL,
                List.<Object[]>of(new Object[] {9L, 1_700_000_000_000L, 1_000L})
            )
            .withScalar(LiveProductSmokeDbProbe.RAW_WITHOUT_CANONICAL_SQL, 2L)
            .withScalar(LiveProductSmokeDbProbe.CURSOR_COMMIT_SEQ_SQL, 44L);
        Output output = new Output();
        LiveProductSmokeDbProbeCli.Config config = new LiveProductSmokeDbProbeCli.Config(
            "pipelineReliabilitySnapshot",
            "jdbc:postgresql://live/db",
            "user",
            "password",
            Map.of("cursor-name", "cursor", "window-seconds", "120", "row-limit", "500"),
            false
        );

        assertEquals(0, LiveProductSmokeDbProbeCli.run(config, jdbc::openConnection, output.out(), output.err()));

        assertEquals("ok|120|500|12|555|800|10|44|900|44|0|9|1700000000000|1000|2\n", output.stdout());
    }

    private static final class Output {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        private PrintStream out() {
            return new PrintStream(stdout, true, StandardCharsets.UTF_8);
        }

        private PrintStream err() {
            return new PrintStream(stderr, true, StandardCharsets.UTF_8);
        }

        private String stdout() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        private String stderr() {
            return stderr.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class RecordingJdbc {
        private final Map<String, Long> scalarResults = new HashMap<>();
        private final Map<String, List<Object[]>> rowResults = new HashMap<>();
        private final List<Map<Integer, Object>> canonicalInserts = new ArrayList<>();
        private int metadataUpserts;
        private int canonicalCommitSeq = 100;
        private int commitCalls;
        private int rollbackCalls;

        private RecordingJdbc withScalar(String sql, long value) {
            scalarResults.put(normalizeSql(sql), value);
            return this;
        }

        private RecordingJdbc withRows(String sql, List<Object[]> rows) {
            rowResults.put(normalizeSql(sql), rows);
            return this;
        }

        private Connection openConnection() {
            InvocationHandler handler = this::handleConnectionInvocation;
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                handler
            );
        }

        private Object handleConnectionInvocation(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getAutoCommit" -> true;
                case "setAutoCommit" -> null;
                case "commit" -> {
                    commitCalls++;
                    yield null;
                }
                case "rollback" -> {
                    rollbackCalls++;
                    yield null;
                }
                case "prepareStatement" -> preparedStatement((String) args[0]);
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement(String sql) {
            InvocationHandler handler = new PreparedStatementHandler(sql, this);
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                handler
            );
        }

        private ResultSet resultSet(long value) {
            return resultSet(List.<Object[]>of(new Object[] {value}));
        }

        private ResultSet emptyResultSet() {
            return resultSet(List.<Object[]>of());
        }

        private ResultSet resultSet(List<Object[]> rows) {
            InvocationHandler handler = new ResultSetHandler(rows);
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                handler
            );
        }

        private static String normalizeSql(String sql) {
            return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        }
    }

    private static final class PreparedStatementHandler implements InvocationHandler {
        private final String sql;
        private final RecordingJdbc jdbc;
        private final Map<Integer, Object> parameters = new HashMap<>();

        private PreparedStatementHandler(String sql, RecordingJdbc jdbc) {
            this.sql = sql;
            this.jdbc = jdbc;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "setString", "setInt", "setLong" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setNull" -> {
                    parameters.put((Integer) args[0], null);
                    yield null;
                }
                case "executeUpdate" -> {
                    if (normalize(sql).equals(normalize(LiveProductSmokeDbProbe.UPSERT_MARKET_METADATA_SQL))) {
                        jdbc.metadataUpserts++;
                    }
                    yield 1;
                }
                case "executeQuery" -> {
                    String normalized = normalize(sql);
                    if (normalized.equals(normalize(LiveProductSmokeDbProbe.INSERT_CANONICAL_EVENT_SQL))) {
                        jdbc.canonicalInserts.add(new HashMap<>(parameters));
                        yield jdbc.resultSet(++jdbc.canonicalCommitSeq);
                    }
                    List<Object[]> rows = jdbc.rowResults.get(normalized);
                    if (rows != null) {
                        yield jdbc.resultSet(rows);
                    }
                    Long scalar = jdbc.scalarResults.get(normalized);
                    if (scalar != null) {
                        yield jdbc.resultSet(scalar);
                    }
                    yield jdbc.emptyResultSet();
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private static String normalize(String sql) {
            return RecordingJdbc.normalizeSql(sql);
        }
    }

    private static final class ResultSetHandler implements InvocationHandler {
        private final List<Object[]> rows;
        private int index = -1;

        private ResultSetHandler(List<Object[]> rows) {
            this.rows = new ArrayList<>(rows);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> {
                    index++;
                    yield index < rows.size();
                }
                case "getString" -> {
                    Object value = rows.get(index)[(Integer) args[0] - 1];
                    yield value == null ? null : String.valueOf(value);
                }
                case "getLong" -> {
                    Object value = rows.get(index)[(Integer) args[0] - 1];
                    if (value instanceof Number number) {
                        yield number.longValue();
                    }
                    yield Long.parseLong(String.valueOf(value));
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
