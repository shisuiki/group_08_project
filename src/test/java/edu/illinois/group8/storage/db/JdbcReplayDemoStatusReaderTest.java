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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcReplayDemoStatusReaderTest {
    @Test
    void readsEmptyReplayStatusSafely() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "market_count", 0L,
            "canonical_event_count", 0L,
            "feature_output_count", 0L,
            "latest_market_state_count", 0L,
            "first_event_ts_ms", null,
            "last_event_ts_ms", null,
            "first_canonical_commit_seq", null,
            "last_canonical_commit_seq", null,
            "available_symbols", ""
        )));
        JdbcReplayDemoStatusReader reader = new JdbcReplayDemoStatusReader(jdbc::openConnection);

        ReplayDemoStatus status = reader.readDefault();

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("from canonical_events"));
        assertTrue(sql.contains("where replay_id = ?"));
        assertTrue(sql.contains("from feature_outputs"));
        assertTrue(sql.contains("from latest_market_state"));
        assertEquals(JdbcReplayDemoStatusReader.DEFAULT_REPLAY_ID, jdbc.parameters.get(1));
        assertEquals(JdbcReplayDemoStatusReader.QUERY_TIMEOUT_SECONDS, jdbc.queryTimeoutSeconds);
        assertEquals("empty", status.status());
        assertEquals(0L, status.marketCount());
        assertEquals(0L, status.canonicalEventCount());
        assertEquals(0L, status.featureOutputCount());
        assertEquals(0L, status.latestMarketStateCount());
        assertEquals(List.of(), status.availableSymbols());
        assertEquals(false, status.featurePlantProjected());
    }

    @Test
    void readsProjectedLongReplayShape() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "market_count", 2L,
            "canonical_event_count", 366L,
            "feature_output_count", 362L,
            "latest_market_state_count", 0L,
            "first_event_ts_ms", 1_800_000_000_000L,
            "last_event_ts_ms", 1_800_000_180_000L,
            "first_canonical_commit_seq", 10L,
            "last_canonical_commit_seq", 375L,
            "available_symbols", "DEMO-DBPRIMARY-26MAY19-T50,DEMO-DBPRIMARY-26MAY19-T60"
        )));
        JdbcReplayDemoStatusReader reader = new JdbcReplayDemoStatusReader(jdbc::openConnection);

        ReplayDemoStatus status = reader.read(" demo-db-primary-long-replay ");

        assertEquals("projected", status.status());
        assertEquals("demo-db-primary-long-replay", status.replayId());
        assertEquals(2L, status.marketCount());
        assertEquals(366L, status.canonicalEventCount());
        assertEquals(362L, status.featureOutputCount());
        assertEquals(0L, status.latestMarketStateCount());
        assertEquals(1_800_000_000_000L, status.firstEventTsMs());
        assertEquals(1_800_000_180_000L, status.lastEventTsMs());
        assertEquals(10L, status.firstCanonicalCommitSeq());
        assertEquals(375L, status.lastCanonicalCommitSeq());
        assertEquals(List.of(
            "DEMO-DBPRIMARY-26MAY19-T50",
            "DEMO-DBPRIMARY-26MAY19-T60"
        ), status.availableSymbols());
        assertTrue(status.featurePlantProjected());
    }

    @Test
    void validatesReplayIdAndWrapsSqlFailure() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new JdbcReplayDemoStatusReader(new RecordingJdbc(List.of())::openConnection).read(" ")
        );

        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        jdbc.failExecuteQuery = true;
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> new JdbcReplayDemoStatusReader(jdbc::openConnection).readDefault()
        );

        assertTrue(thrown.getMessage().contains("replay demo status"));
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
        private int queryTimeoutSeconds;
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
                case "setQueryTimeout" -> {
                    queryTimeoutSeconds = (Integer) args[0];
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
