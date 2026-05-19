package edu.illinois.group8.storage.db;

import edu.illinois.group8.feature.FeatureOutput;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcFeatureOutputReaderTest {
    @Test
    void implementsFeatureOutputReaderContract() {
        JdbcFeatureOutputReader reader = new JdbcFeatureOutputReader(() -> {
            throw new SQLException("unused");
        });

        assertInstanceOf(FeatureOutputReader.class, reader);
    }

    @Test
    void defaultReadIsBoundedOrderedAndMapsRows() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "feature_event_id", "feature-1",
            "source_event_id", "source-1",
            "feature_name", "feature.bbo",
            "feature_version", 1,
            "market_ticker", "MKT-1",
            "event_ts_ms", 1000L,
            "created_at", Instant.parse("2026-05-20T00:00:01Z"),
            "values", "{\"midpoint_micros\":123,\"score\":1.25,\"active\":true}"
        )));
        JdbcFeatureOutputReader reader = new JdbcFeatureOutputReader(jdbc::openConnection);

        List<FeatureOutput> outputs = reader.read(null);

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("from feature_outputs"));
        assertTrue(sql.contains("where 1 = 1"));
        assertTrue(sql.contains("order by event_ts_ms desc nulls last, feature_event_id asc"));
        assertTrue(sql.contains("limit ?"));
        assertFalse(sql.contains("feature_name in"));
        assertEquals(List.of(FeatureOutputReadRequest.DEFAULT_MAX_ROWS), jdbc.bindings);

        assertEquals(1, outputs.size());
        FeatureOutput output = outputs.get(0);
        assertEquals("feature.bbo", output.featureName());
        assertEquals("feature.bbo", output.streamName());
        assertEquals("MKT-1", output.marketTicker());
        assertEquals(1000L, output.eventTsMs());
        assertEquals("source-1", output.sourceEventId());
        assertEquals(123L, ((Number) output.values().get("midpoint_micros")).longValue());
        assertEquals(1.25D, ((Number) output.values().get("score")).doubleValue());
        assertEquals(true, output.values().get("active"));
        assertEquals(1, jdbc.resultSetCloseCalls);
        assertEquals(1, jdbc.preparedStatementCloseCalls);
        assertEquals(1, jdbc.connectionCloseCalls);
    }

    @Test
    void filtersBindInStableOrder() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcFeatureOutputReader reader = new JdbcFeatureOutputReader(jdbc::openConnection);

        reader.read(new FeatureOutputReadRequest(
            List.of("feature.bbo", "feature.trade_tape"),
            "MKT-1",
            1000L,
            2000L,
            25
        ));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("feature_name in (?, ?)"));
        assertTrue(sql.contains("market_ticker = ?"));
        assertTrue(sql.contains("event_ts_ms >= ?"));
        assertTrue(sql.contains("event_ts_ms <= ?"));
        assertTrue(sql.contains("limit ?"));
        assertEquals(List.of("feature.bbo", "feature.trade_tape", "MKT-1", 1000L, 2000L, 25), jdbc.bindings);
    }

    @Test
    void requestNormalizesBlankFiltersAndRejectsInvalidBounds() {
        FeatureOutputReadRequest request = new FeatureOutputReadRequest(
            List.of("feature.bbo", " ", "feature.bbo"),
            " ",
            null,
            null,
            10
        );

        assertEquals(List.of("feature.bbo"), request.featureNames());
        assertEquals(null, request.marketTicker());
        assertThrows(
            IllegalArgumentException.class,
            () -> new FeatureOutputReadRequest(List.of(), null, 200L, 100L, 10)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new FeatureOutputReadRequest(List.of(), null, null, null, 0)
        );
    }

    @Test
    void nullEventTimestampMapsToNull() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "feature_event_id", "feature-1",
            "source_event_id", null,
            "feature_name", "feature.trade_tape",
            "feature_version", 1,
            "market_ticker", "MKT-1",
            "event_ts_ms", null,
            "created_at", Instant.parse("2026-05-20T00:00:01Z"),
            "values", "{\"trade_id\":\"T1\"}"
        )));
        JdbcFeatureOutputReader reader = new JdbcFeatureOutputReader(jdbc::openConnection);

        List<FeatureOutput> outputs = reader.read(FeatureOutputReadRequest.recent(List.of("feature.trade_tape"), 5));

        assertEquals(null, outputs.get(0).eventTsMs());
        assertEquals(null, outputs.get(0).sourceEventId());
    }

    @Test
    void sqlFailureWrapsWithTableContext() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        jdbc.failExecuteQuery = true;
        JdbcFeatureOutputReader reader = new JdbcFeatureOutputReader(jdbc::openConnection);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> reader.read(FeatureOutputReadRequest.defaultRecent())
        );

        assertTrue(thrown.getMessage().contains("feature_outputs"));
        assertInstanceOf(SQLException.class, thrown.getCause());
    }

    @Test
    void invalidValuesJsonFailsWithRowContext() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "feature_event_id", "feature-bad",
            "source_event_id", null,
            "feature_name", "feature.bad",
            "feature_version", 1,
            "market_ticker", "MKT-1",
            "event_ts_ms", 1L,
            "created_at", Instant.parse("2026-05-20T00:00:01Z"),
            "values", "{bad-json"
        )));
        JdbcFeatureOutputReader reader = new JdbcFeatureOutputReader(jdbc::openConnection);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> reader.read(FeatureOutputReadRequest.defaultRecent())
        );

        assertTrue(thrown.getMessage().contains("feature-bad"));
    }

    @Test
    void readsRowsWithStableCreatedAtCursor() {
        Instant cursorTime = Instant.parse("2026-05-20T00:00:01Z");
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "feature_event_id", "feature-2",
            "source_event_id", "source-2",
            "feature_name", "feature.bbo",
            "feature_version", 1,
            "market_ticker", "MKT-1",
            "event_ts_ms", 2000L,
            "created_at", Instant.parse("2026-05-20T00:00:02Z"),
            "values", "{\"midpoint_micros\":456}"
        )));
        JdbcFeatureOutputReader reader = new JdbcFeatureOutputReader(jdbc::openConnection);

        List<FeatureOutputRow> rows = reader.readRows(FeatureOutputReadRequest.afterCreatedAt(
            List.of("feature.bbo"),
            new FeatureOutputCursor(cursorTime, "feature-1"),
            10
        ));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("created_at > ? or (created_at = ? and feature_event_id > ?)"));
        assertTrue(sql.contains("order by created_at asc, feature_event_id asc"));
        assertEquals(List.of(
            "feature.bbo",
            Timestamp.from(cursorTime),
            Timestamp.from(cursorTime),
            "feature-1",
            10
        ), jdbc.bindings);
        assertEquals(1, rows.size());
        assertEquals("feature-2", rows.get(0).featureEventId());
        assertEquals(Instant.parse("2026-05-20T00:00:02Z"), rows.get(0).createdAt());
        assertEquals(456L, ((Number) rows.get(0).output().values().get("midpoint_micros")).longValue());
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            row.put((String) keyValues[index], keyValues[index + 1]);
        }
        return row;
    }

    private static final class RecordingJdbc {
        private final List<Map<String, Object>> rows;
        private final List<Object> bindings = new ArrayList<>();
        private String preparedSql;
        private boolean failExecuteQuery;
        private int connectionCloseCalls;
        private int preparedStatementCloseCalls;
        private int resultSetCloseCalls;

        private RecordingJdbc(List<Map<String, Object>> rows) {
            this.rows = rows;
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
                case "prepareStatement" -> {
                    preparedSql = (String) args[0];
                    yield preparedStatement();
                }
                case "close" -> {
                    connectionCloseCalls++;
                    yield null;
                }
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

        private Object handlePreparedStatementInvocation(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "setObject" -> {
                    int parameterIndex = (Integer) args[0];
                    while (bindings.size() < parameterIndex) {
                        bindings.add(null);
                    }
                    bindings.set(parameterIndex - 1, args[1]);
                    yield null;
                }
                case "executeQuery" -> {
                    if (failExecuteQuery) {
                        throw new SQLException("executeQuery failed");
                    }
                    yield resultSet();
                }
                case "close" -> {
                    preparedStatementCloseCalls++;
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet() {
            InvocationHandler handler = new ResultSetHandler(rows, () -> resultSetCloseCalls++);
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                handler
            );
        }
    }

    private static final class ResultSetHandler implements InvocationHandler {
        private final List<Map<String, Object>> rows;
        private final Runnable closeCallback;
        private int rowIndex = -1;
        private boolean lastWasNull;

        private ResultSetHandler(List<Map<String, Object>> rows, Runnable closeCallback) {
            this.rows = rows;
            this.closeCallback = closeCallback;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> {
                    rowIndex++;
                    yield rowIndex < rows.size();
                }
                case "getString" -> stringValue((String) args[0]);
                case "getInt" -> intValue((String) args[0]);
                case "getLong" -> longValue((String) args[0]);
                case "getObject" -> objectValue((String) args[0]);
                case "wasNull" -> lastWasNull;
                case "close" -> {
                    closeCallback.run();
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        private String stringValue(String column) {
            Object value = objectValue(column);
            return value == null ? null : value.toString();
        }

        private int intValue(String column) {
            Object value = objectValue(column);
            return value == null ? 0 : ((Number) value).intValue();
        }

        private long longValue(String column) {
            Object value = objectValue(column);
            return value == null ? 0L : ((Number) value).longValue();
        }

        private Object objectValue(String column) {
            Object value = rows.get(rowIndex).get(column);
            lastWasNull = value == null;
            return value;
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
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
