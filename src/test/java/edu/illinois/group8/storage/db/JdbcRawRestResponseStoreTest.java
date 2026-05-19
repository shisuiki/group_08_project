package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRawRestResponseStoreTest {
    private static final String RAW_PAYLOAD = "{\"markets\":[{\"ticker\":\"M\"}]}";

    @Test
    void emptyBatchDoesNotOpenConnection() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcRawRestResponseStore store = new JdbcRawRestResponseStore(jdbc::openConnection);

        store.insertRawRestResponseBatch(List.of());

        assertEquals(0, jdbc.openConnections);
    }

    @Test
    void sqlUsesRawRestResponsesTextPayloadAndConflictIgnore() {
        String sql = JdbcRawRestResponseStore.RAW_REST_INSERT_SQL.toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("raw_rest_responses"));
        assertTrue(sql.contains("raw_rest_response_id"));
        assertTrue(sql.contains("raw_payload"));
        assertTrue(sql.contains("on conflict do nothing"));
        assertFalse(sql.contains("raw_ws_events"));
        assertFalse(sql.contains("?::jsonb"));
    }

    @Test
    void rawRestResponseBatchBindsPayloadTextAndCommits() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcRawRestResponseStore store = new JdbcRawRestResponseStore(jdbc::openConnection);
        RawRestDbResponse response = response("raw_rest_1", null);

        store.insertRawRestResponseBatch(List.of(response));

        assertEquals(1, jdbc.openConnections);
        assertEquals(1, jdbc.commitCalls);
        assertEquals(0, jdbc.rollbackCalls);
        assertEquals(JdbcRawRestResponseStore.RAW_REST_INSERT_SQL, jdbc.preparedSql);
        assertEquals(List.of(false, true), jdbc.autoCommitValues);
        Map<Integer, Object> row = jdbc.batches.get(0);
        assertEquals("raw_rest_1", row.get(1));
        assertEquals("rest.markets", row.get(2));
        assertEquals(null, row.get(3));
        assertEquals(123L, row.get(4));
        assertInstanceOf(OffsetDateTime.class, row.get(5));
        assertEquals("sha256", row.get(6));
        assertEquals(RAW_PAYLOAD, row.get(7));
    }

    @Test
    void failedBatchRollsBackAndRethrows() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.failExecute = true;
        JdbcRawRestResponseStore store = new JdbcRawRestResponseStore(jdbc::openConnection);

        SQLException thrown = assertThrows(
            SQLException.class,
            () -> store.insertRawRestResponseBatch(List.of(response("raw_rest_fail", "M")))
        );

        assertEquals("execute failed", thrown.getMessage());
        assertEquals(0, jdbc.commitCalls);
        assertEquals(1, jdbc.rollbackCalls);
    }

    private static RawRestDbResponse response(String id, String ticker) {
        return new RawRestDbResponse(
            id,
            "rest.markets",
            ticker,
            123L,
            Instant.parse("2026-05-19T00:00:00Z"),
            "sha256",
            RAW_PAYLOAD
        );
    }

    private static final class RecordingJdbc {
        private int openConnections;
        private int commitCalls;
        private int rollbackCalls;
        private String preparedSql;
        private boolean autoCommit = true;
        private boolean failExecute;
        private final List<Boolean> autoCommitValues = new ArrayList<>();
        private final List<Map<Integer, Object>> batches = new ArrayList<>();

        private Connection openConnection() {
            openConnections++;
            InvocationHandler handler = this::handleConnectionInvocation;
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                handler
            );
        }

        private Object handleConnectionInvocation(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> {
                    autoCommit = (Boolean) args[0];
                    autoCommitValues.add(autoCommit);
                    yield null;
                }
                case "prepareStatement" -> {
                    preparedSql = (String) args[0];
                    yield preparedStatement();
                }
                case "commit" -> {
                    commitCalls++;
                    yield null;
                }
                case "rollback" -> {
                    rollbackCalls++;
                    yield null;
                }
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement() {
            Map<Integer, Object> parameters = new HashMap<>();
            InvocationHandler handler = (proxy, method, args) ->
                handlePreparedStatementInvocation(method, args, parameters);
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                handler
            );
        }

        private Object handlePreparedStatementInvocation(
            Method method,
            Object[] args,
            Map<Integer, Object> parameters
        ) throws SQLException {
            return switch (method.getName()) {
                case "setString", "setLong", "setObject" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "addBatch" -> {
                    batches.add(new HashMap<>(parameters));
                    parameters.clear();
                    yield null;
                }
                case "executeBatch" -> {
                    if (failExecute) {
                        throw new SQLException("execute failed");
                    }
                    int[] result = new int[batches.size()];
                    Arrays.fill(result, 1);
                    yield result;
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
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
}
