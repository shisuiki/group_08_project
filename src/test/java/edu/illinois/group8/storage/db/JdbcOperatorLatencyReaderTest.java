package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOperatorLatencyReaderTest {
    @Test
    void readsLatencyForSourceEvent() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "event_id", "evt-1",
            "market_ticker", "MKT",
            "canonical_commit_seq", 42L,
            "latest_market_state_commit_seq", 42L,
            "canonical_to_feature_ms", 5L,
            "feature_to_latest_state_ms", 7L,
            "canonical_to_latest_state_ms", 12L
        )));
        JdbcOperatorLatencyReader reader = new JdbcOperatorLatencyReader(jdbc::openConnection);

        OperatorLatencyStatus status = reader.read(" evt-1 ");

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("from canonical_events"));
        assertTrue(sql.contains("from feature_outputs"));
        assertTrue(sql.contains("from latest_market_state"));
        assertEquals("evt-1", jdbc.parameters.get(1));
        assertEquals("ok", status.status());
        assertEquals("evt-1", status.sourceEventId());
        assertEquals("MKT", status.marketTicker());
        assertEquals(42L, status.canonicalCommitSeq());
        assertEquals(42L, status.latestMarketStateCommitSeq());
        assertEquals(5L, status.canonicalToFeatureMs());
        assertEquals(7L, status.featureToLatestStateMs());
        assertEquals(12L, status.canonicalToLatestStateMs());
        assertNull(status.reason());
    }

    @Test
    void mapsMissingAndDegradedLatency() {
        OperatorLatencyStatus missing = new JdbcOperatorLatencyReader(
            new RecordingJdbc(List.of())::openConnection
        ).read("missing");
        OperatorLatencyStatus degraded = new JdbcOperatorLatencyReader(
            new RecordingJdbc(List.of(row(
                "event_id", "evt-2",
                "market_ticker", "MKT",
                "canonical_commit_seq", 43L,
                "latest_market_state_commit_seq", -1L,
                "canonical_to_feature_ms", 4L,
                "feature_to_latest_state_ms", -1L,
                "canonical_to_latest_state_ms", -1L
            )))::openConnection
        ).read("evt-2");

        assertEquals("missing", missing.status());
        assertEquals("missing_canonical_event", missing.reason());
        assertEquals("degraded", degraded.status());
        assertEquals("missing_latest_market_state", degraded.reason());
        assertEquals(4L, degraded.canonicalToFeatureMs());
        assertNull(degraded.latestMarketStateCommitSeq());
        assertNull(degraded.canonicalToLatestStateMs());
    }

    @Test
    void validatesSourceEventAndWrapsSqlFailure() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new JdbcOperatorLatencyReader(new RecordingJdbc(List.of())::openConnection).read(" ")
        );

        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        jdbc.failExecuteQuery = true;
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> new JdbcOperatorLatencyReader(jdbc::openConnection).read("evt")
        );

        assertTrue(thrown.getMessage().contains("operator latency"));
        assertInstanceOf(SQLException.class, thrown.getCause());
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
        private final Map<Integer, Object> parameters = new HashMap<>();
        private int rowIndex = -1;
        private String preparedSql;
        private boolean failExecuteQuery;

        private RecordingJdbc(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        private Connection openConnection() {
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                this::handleConnection
            );
        }

        private Object handleConnection(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "prepareStatement" -> {
                    preparedSql = (String) args[0];
                    yield preparedStatement();
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                this::handlePreparedStatement
            );
        }

        private Object handlePreparedStatement(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "setString" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "executeQuery" -> {
                    if (failExecuteQuery) {
                        throw new SQLException("query failed");
                    }
                    yield resultSet();
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet() {
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                this::handleResultSet
            );
        }

        private Object handleResultSet(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> {
                    rowIndex++;
                    yield rowIndex < rows.size();
                }
                case "getObject" -> rows.get(rowIndex).get((String) args[0]);
                case "getString" -> {
                    Object value = rows.get(rowIndex).get((String) args[0]);
                    yield value == null ? null : String.valueOf(value);
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == byte.class || type == short.class || type == int.class || type == long.class) {
                return 0;
            }
            if (type == float.class || type == double.class) {
                return 0.0;
            }
            return null;
        }
    }
}
