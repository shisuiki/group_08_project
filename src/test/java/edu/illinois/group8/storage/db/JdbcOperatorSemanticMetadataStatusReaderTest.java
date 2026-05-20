package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOperatorSemanticMetadataStatusReaderTest {
    @Test
    void readsSemanticMetadataStatusWithoutSelectingPayloads() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "generated_count", 3L,
            "review_required_count", 1L,
            "failed_count", 2L,
            "rate_limited_count", 4L,
            "last_generated_at", OffsetDateTime.parse("2026-01-01T00:00:00Z")
        )));
        JdbcOperatorSemanticMetadataStatusReader reader =
            new JdbcOperatorSemanticMetadataStatusReader(jdbc::openConnection, "model", "fallback", "tax-v1");

        OperatorSemanticMetadataStatus status = reader.read();

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("from market_semantic_metadata"));
        assertTrue(sql.contains("where taxonomy_version = ?"));
        assertTrue(!sql.contains("raw_response"));
        assertEquals("tax-v1", jdbc.parameters.get(1));
        assertEquals("ok", status.status());
        assertTrue(status.configured());
        assertEquals("model", status.model());
        assertEquals("fallback", status.fallbackModel());
        assertEquals("tax-v1", status.taxonomyVersion());
        assertEquals(3L, status.generatedCount());
        assertEquals(1L, status.reviewRequiredCount());
        assertEquals(2L, status.failedCount());
        assertEquals(4L, status.rateLimitedCount());
        assertNotNull(status.lastGeneratedAt());
        assertNotNull(status.lastGeneratedAgeMs());
    }

    @Test
    void mapsStaleAndWrapsSqlFailure() {
        OperatorSemanticMetadataStatus stale = new JdbcOperatorSemanticMetadataStatusReader(
            new RecordingJdbc(List.of(row(
                "generated_count", 0L,
                "review_required_count", 0L,
                "failed_count", 0L,
                "rate_limited_count", 0L,
                "last_generated_at", null
            )))::openConnection,
            "model",
            "fallback",
            "tax"
        ).read();
        assertEquals("stale", stale.status());

        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        jdbc.failExecuteQuery = true;
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> new JdbcOperatorSemanticMetadataStatusReader(jdbc::openConnection, "m", "f", "tax").read()
        );
        assertTrue(thrown.getMessage().contains("market_semantic_metadata"));
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
