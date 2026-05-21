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

class JdbcOperatorPipelineStatusReaderTest {
    @Test
    void readsLiveScopedPipelineStatusAndLag() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "cursor_commit_seq", 8L,
            "canonical_max_commit_seq", 13L,
            "latest_market_state_commit_seq", 7L,
            "latest_state_age_ms", 250L,
            "recent_canonical_events", 3L,
            "recent_feature_outputs", 2L,
            "recent_latest_market_states", 1L
        )));
        JdbcOperatorPipelineStatusReader reader = new JdbcOperatorPipelineStatusReader(jdbc::openConnection);

        OperatorPipelineStatus status = reader.read(" featureplant-prod ");

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("from featureplant_cursors"));
        assertTrue(sql.contains("from canonical_events"));
        assertTrue(sql.contains("replay_id is null"));
        assertTrue(sql.contains("from latest_market_state"));
        assertEquals("featureplant-prod", jdbc.parameters.get(1));
        assertEquals(JdbcOperatorPipelineStatusReader.RECENT_WINDOW_SECONDS, jdbc.parameters.get(2));
        assertEquals(JdbcOperatorPipelineStatusReader.RECENT_WINDOW_SECONDS, jdbc.parameters.get(3));
        assertEquals(JdbcOperatorPipelineStatusReader.RECENT_WINDOW_SECONDS, jdbc.parameters.get(4));
        assertEquals(JdbcOperatorPipelineStatusReader.QUERY_TIMEOUT_SECONDS, jdbc.queryTimeoutSeconds);
        assertEquals("degraded", status.status());
        assertEquals("featureplant-prod", status.cursorName());
        assertEquals(8L, status.cursorCommitSeq());
        assertEquals(13L, status.canonicalMaxCommitSeq());
        assertEquals(5L, status.cursorLagEvents());
        assertEquals(7L, status.latestMarketStateCommitSeq());
        assertEquals(250L, status.latestStateAgeMs());
        assertEquals(3L, status.recentCanonicalEvents());
        assertEquals(2L, status.recentFeatureOutputs());
        assertEquals(1L, status.recentLatestMarketStates());
        assertEquals(JdbcOperatorPipelineStatusReader.RECENT_WINDOW_SECONDS, status.recentWindowSeconds());
    }

    @Test
    void mapsOkAndStaleStatus() {
        JdbcOperatorPipelineStatusReader okReader = new JdbcOperatorPipelineStatusReader(
            new RecordingJdbc(List.of(row(
                "cursor_commit_seq", 13L,
                "canonical_max_commit_seq", 13L,
                "latest_market_state_commit_seq", 13L,
                "latest_state_age_ms", 15L,
                "recent_canonical_events", 1L,
                "recent_feature_outputs", 1L,
                "recent_latest_market_states", 1L
            )))::openConnection
        );
        JdbcOperatorPipelineStatusReader staleReader = new JdbcOperatorPipelineStatusReader(
            new RecordingJdbc(List.of(row(
                "cursor_commit_seq", 0L,
                "canonical_max_commit_seq", 0L,
                "latest_market_state_commit_seq", null,
                "latest_state_age_ms", null,
                "recent_canonical_events", 0L,
                "recent_feature_outputs", 0L,
                "recent_latest_market_states", 0L
            )))::openConnection
        );

        assertEquals("ok", okReader.read("cursor").status());
        assertEquals("stale", staleReader.read("cursor").status());
    }

    @Test
    void validatesCursorNameAndWrapsSqlFailure() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new JdbcOperatorPipelineStatusReader(new RecordingJdbc(List.of())::openConnection).read(" ")
        );

        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        jdbc.failExecuteQuery = true;
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> new JdbcOperatorPipelineStatusReader(jdbc::openConnection).read("featureplant-prod")
        );

        assertTrue(thrown.getMessage().contains("operator pipeline status"));
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
                case "setLong" -> {
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
