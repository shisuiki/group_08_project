package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcFeatureOutputStoreTest {
    @Test
    void migrationCreatesFeatureOutputsTableAndIndexes() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("create table if not exists feature_outputs"));
        assertTrue(sql.contains("feature_event_id text primary key"));
        assertTrue(sql.contains("source_event_id text"));
        assertTrue(sql.contains("feature_name text not null"));
        assertTrue(sql.contains("feature_version integer not null"));
        assertTrue(sql.contains("market_ticker text"));
        assertTrue(sql.contains("event_ts_ms bigint"));
        assertTrue(sql.contains("\"values\" jsonb not null"));
        assertTrue(sql.contains("created_at timestamptz not null default now()"));
        assertTrue(sql.contains("feature_outputs_feature_market_event_ts_idx"));
        assertTrue(sql.contains("on feature_outputs (feature_name, market_ticker, event_ts_ms)"));
    }

    @Test
    void migrationUsesNullNormalizedUniqueKeyForIdempotentRetries() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("feature_outputs_feature_source_market_uidx"));
        assertTrue(sql.contains("feature_name"));
        assertTrue(sql.contains("feature_version"));
        assertTrue(sql.contains("(source_event_id is null)"));
        assertTrue(sql.contains("coalesce(source_event_id, '')"));
        assertTrue(sql.contains("(market_ticker is null)"));
        assertTrue(sql.contains("coalesce(market_ticker, '')"));
        assertTrue(sql.contains("plain postgres unique indexes allow repeated null keys"));
    }

    @Test
    void refreshCursorMigrationIndexesCreatedAtCursorReads() throws Exception {
        String sql = migrationSql("db/migration/V009__feature_output_refresh_cursor_index.sql")
            .toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("feature_outputs_created_cursor_idx"));
        assertTrue(sql.contains("on feature_outputs (created_at, feature_event_id, feature_name)"));
    }

    @Test
    void insertSqlUsesJsonbValuesAndConflictIgnore() {
        String sql = JdbcFeatureOutputStore.INSERT_SQL.toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("insert into feature_outputs"));
        assertTrue(sql.contains("\"values\""));
        assertTrue(sql.contains("values (?, ?, ?, ?, ?, ?, ?::jsonb)"));
        assertTrue(sql.contains("on conflict do nothing"));
    }

    @Test
    void insertBindsNullableFieldsAndJsonValues() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcFeatureOutputStore store = new JdbcFeatureOutputStore(jdbc::openConnection);
        FeatureOutputDbEvent output = new FeatureOutputDbEvent(
            "feature-event-1",
            null,
            "feature.bbo",
            2,
            null,
            null,
            "{\"midpoint_micros\":123}"
        );

        store.insertFeatureOutput(output);

        assertEquals(1, jdbc.openConnections);
        assertEquals(0, jdbc.executeUpdateCalls);
        assertEquals(1, jdbc.executeBatchCalls);
        assertEquals(1, jdbc.commitCalls);
        assertEquals(List.of(JdbcFeatureOutputStore.INSERT_SQL), jdbc.preparedSqls);
        assertEquals("feature-event-1", jdbc.batchParameters.get(0).get(1));
        assertEquals(null, jdbc.batchParameters.get(0).get(2));
        assertEquals("feature.bbo", jdbc.batchParameters.get(0).get(3));
        assertEquals(2, jdbc.batchParameters.get(0).get(4));
        assertEquals(null, jdbc.batchParameters.get(0).get(5));
        assertEquals(new SqlNull(Types.BIGINT), jdbc.batchParameters.get(0).get(6));
        assertEquals("{\"midpoint_micros\":123}", jdbc.batchParameters.get(0).get(7));
    }

    @Test
    void insertBindsSourceMarketAndEventTimestampWhenPresent() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcFeatureOutputStore store = new JdbcFeatureOutputStore(jdbc::openConnection);
        FeatureOutputDbEvent output = new FeatureOutputDbEvent(
            "feature-event-2",
            "canonical-1",
            "feature.trade_tape",
            3,
            "MARKET-1",
            123456L,
            "{\"last_price_micros\":456}"
        );

        store.insertFeatureOutput(output);

        assertEquals("canonical-1", jdbc.batchParameters.get(0).get(2));
        assertEquals("MARKET-1", jdbc.batchParameters.get(0).get(5));
        assertEquals(123456L, jdbc.batchParameters.get(0).get(6));
        assertEquals(1, jdbc.executeQueryCalls);
        assertTrue(jdbc.preparedSqls.get(1).contains("market_feature_stats"));
        assertEquals("MARKET-1", jdbc.parameters.get(1));
    }

    @Test
    void batchInsertUsesOneConnectionPreparedStatementAndTransaction() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcFeatureOutputStore store = new JdbcFeatureOutputStore(jdbc::openConnection);

        store.insertFeatureOutputBatch(List.of(
            new FeatureOutputDbEvent("feature-event-1", "source-1", "feature.bbo", 1, "M1", 1L, "{}"),
            new FeatureOutputDbEvent("feature-event-2", null, "feature.ticker_snapshot", 1, null, null, "{\"x\":1}")
        ));

        assertEquals(1, jdbc.openConnections);
        assertEquals(0, jdbc.executeUpdateCalls);
        assertEquals(2, jdbc.addBatchCalls);
        assertEquals(1, jdbc.executeBatchCalls);
        assertEquals(1, jdbc.commitCalls);
        assertEquals(0, jdbc.rollbackCalls);
        assertEquals(List.of(false, true), jdbc.autoCommitSetValues);
        assertEquals(JdbcFeatureOutputStore.INSERT_SQL, jdbc.preparedSqls.get(0));
        assertTrue(jdbc.preparedSqls.get(1).contains("market_feature_stats"));
        assertEquals(1, jdbc.executeQueryCalls);
        assertEquals("M1", jdbc.parameters.get(1));
        assertEquals(2, jdbc.batchParameters.size());
        assertEquals("feature-event-1", jdbc.batchParameters.get(0).get(1));
        assertEquals("feature-event-2", jdbc.batchParameters.get(1).get(1));
        assertEquals(new SqlNull(Types.BIGINT), jdbc.batchParameters.get(1).get(6));
    }

    @Test
    void emptyBatchDoesNotOpenConnection() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcFeatureOutputStore store = new JdbcFeatureOutputStore(jdbc::openConnection);

        store.insertFeatureOutputBatch(List.of());

        assertEquals(0, jdbc.openConnections);
    }

    @Test
    void batchInsertRollsBackOnFailure() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.executeBatchFailure = new SQLException("batch failed");
        JdbcFeatureOutputStore store = new JdbcFeatureOutputStore(jdbc::openConnection);

        assertThrows(Exception.class, () -> store.insertFeatureOutputBatch(List.of(
            new FeatureOutputDbEvent("feature-event-1", "source-1", "feature.bbo", 1, "M1", 1L, "{}")
        )));

        assertEquals(1, jdbc.executeBatchCalls);
        assertEquals(0, jdbc.commitCalls);
        assertEquals(1, jdbc.rollbackCalls);
        assertEquals(List.of(false, true), jdbc.autoCommitSetValues);
    }

    @Test
    void defaultBatchMethodFallsBackToSingleInserts() throws Exception {
        CapturingStore store = new CapturingStore();

        store.insertFeatureOutputBatch(List.of(
            new FeatureOutputDbEvent("feature-event-1", "source-1", "feature.bbo", 1, "M1", 1L, "{}"),
            new FeatureOutputDbEvent("feature-event-2", "source-2", "feature.bbo", 1, "M2", 2L, "{}")
        ));

        assertEquals(List.of("feature-event-1", "feature-event-2"), store.featureEventIds);
    }

    @Test
    void recordValidationRejectsMissingIdentityNameAndValues() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new FeatureOutputDbEvent(" ", "source", "feature.bbo", 1, "M", 1L, "{}")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new FeatureOutputDbEvent("feature-event", "source", " ", 1, "M", 1L, "{}")
        );
        assertThrows(
            NullPointerException.class,
            () -> new FeatureOutputDbEvent("feature-event", "source", "feature.bbo", 1, "M", 1L, null)
        );
    }

    private static String migrationSql() throws Exception {
        return migrationSql("db/migration/V006__feature_outputs.sql");
    }

    private static String migrationSql(String resource) throws Exception {
        try (InputStream inputStream = JdbcFeatureOutputStoreTest.class.getClassLoader()
            .getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("missing migration resource " + resource);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record SqlNull(int sqlType) {
    }

    private static final class RecordingJdbc {
        private int openConnections;
        private int executeUpdateCalls;
        private int addBatchCalls;
        private int executeBatchCalls;
        private int commitCalls;
        private int rollbackCalls;
        private int executeQueryCalls;
        private final List<String> preparedSqls = new ArrayList<>();
        private final Map<Integer, Object> parameters = new HashMap<>();
        private final List<Map<Integer, Object>> batchParameters = new ArrayList<>();
        private final List<Boolean> autoCommitSetValues = new ArrayList<>();
        private SQLException executeBatchFailure;

        private Connection openConnection() {
            openConnections++;
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
                case "setAutoCommit" -> {
                    autoCommitSetValues.add((Boolean) args[0]);
                    yield null;
                }
                case "commit" -> {
                    commitCalls++;
                    yield null;
                }
                case "rollback" -> {
                    rollbackCalls++;
                    yield null;
                }
                case "prepareStatement" -> {
                    preparedSqls.add((String) args[0]);
                    yield preparedStatement();
                }
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement() {
            InvocationHandler handler = this::handlePreparedStatementInvocation;
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                handler
            );
        }

        private Object handlePreparedStatementInvocation(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "setString", "setInt", "setLong" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setNull" -> {
                    parameters.put((Integer) args[0], new SqlNull((Integer) args[1]));
                    yield null;
                }
                case "executeUpdate" -> {
                    executeUpdateCalls++;
                    yield 1;
                }
                case "addBatch" -> {
                    addBatchCalls++;
                    batchParameters.add(new HashMap<>(parameters));
                    yield null;
                }
                case "executeBatch" -> {
                    executeBatchCalls++;
                    if (executeBatchFailure != null) {
                        throw executeBatchFailure;
                    }
                    yield new int[addBatchCalls];
                }
                case "executeQuery" -> {
                    executeQueryCalls++;
                    yield resultSet();
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> false;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                handler
            );
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

    private static final class CapturingStore implements FeatureOutputStore {
        private final List<String> featureEventIds = new ArrayList<>();

        @Override
        public void insertFeatureOutput(FeatureOutputDbEvent output) {
            featureEventIds.add(output.featureEventId());
        }
    }
}
