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
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcFeaturePlantCursorStoreTest {
    @Test
    void migrationCreatesFeatureplantCursorTable() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("create table if not exists featureplant_cursors"));
        assertTrue(sql.contains("cursor_name text primary key"));
        assertTrue(sql.contains("last_commit_seq bigint not null"));
        assertTrue(sql.contains("updated_at timestamptz not null default now()"));
        assertTrue(sql.contains("check (last_commit_seq >= 0)"));
        assertTrue(sql.contains("featureplant_cursors_updated_at_idx"));
    }

    @Test
    void loadCursorBindsNameAndMapsCommitSeq() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row("last_commit_seq", 42L)));
        JdbcFeaturePlantCursorStore store = new JdbcFeaturePlantCursorStore(jdbc::openConnection);

        Optional<CanonicalDbCursor> cursor = store.loadCursor(" featureplant-prod ");

        assertTrue(cursor.isPresent());
        assertEquals(42L, cursor.orElseThrow().lastCommitSeq());
        assertEquals(JdbcFeaturePlantCursorStore.SELECT_SQL, jdbc.preparedSql);
        assertEquals("featureplant-prod", jdbc.parameters.get(1));
        assertEquals(1, jdbc.executeQueryCalls);
        assertEquals(1, jdbc.resultSetCloseCalls);
        assertEquals(1, jdbc.preparedStatementCloseCalls);
        assertEquals(1, jdbc.connectionCloseCalls);
    }

    @Test
    void loadCursorReturnsEmptyWhenMissing() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcFeaturePlantCursorStore store = new JdbcFeaturePlantCursorStore(jdbc::openConnection);

        assertFalse(store.loadCursor("featureplant-prod").isPresent());
    }

    @Test
    void saveCursorUsesUpsertAndBindsCommitSeq() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcFeaturePlantCursorStore store = new JdbcFeaturePlantCursorStore(jdbc::openConnection);

        store.saveCursor(" featureplant-prod ", new CanonicalDbCursor(12L));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("insert into featureplant_cursors"));
        assertTrue(sql.contains("on conflict (cursor_name) do update"));
        assertTrue(sql.contains("last_commit_seq = greatest(featureplant_cursors.last_commit_seq, excluded.last_commit_seq)"));
        assertEquals(JdbcFeaturePlantCursorStore.UPSERT_SQL, jdbc.preparedSql);
        assertEquals("featureplant-prod", jdbc.parameters.get(1));
        assertEquals(12L, jdbc.parameters.get(2));
        assertEquals(1, jdbc.executeUpdateCalls);
    }

    @Test
    void validatesCursorNameAndCursor() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcFeaturePlantCursorStore store = new JdbcFeaturePlantCursorStore(jdbc::openConnection);

        assertThrows(IllegalArgumentException.class, () -> store.loadCursor(" "));
        assertThrows(IllegalArgumentException.class, () -> store.saveCursor(null, new CanonicalDbCursor(1L)));
        assertThrows(NullPointerException.class, () -> store.saveCursor("cursor", null));
    }

    @Test
    void sqlFailuresAreWrappedWithContext() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        jdbc.failExecuteQuery = true;
        JdbcFeaturePlantCursorStore store = new JdbcFeaturePlantCursorStore(jdbc::openConnection);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> store.loadCursor("featureplant-prod")
        );

        assertTrue(thrown.getMessage().contains("featureplant cursor featureplant-prod"));
        assertInstanceOf(SQLException.class, thrown.getCause());
    }

    private static String migrationSql() throws Exception {
        try (InputStream inputStream = JdbcFeaturePlantCursorStoreTest.class.getClassLoader()
            .getResourceAsStream("db/migration/V010__featureplant_cursors.sql")) {
            if (inputStream == null) {
                throw new IllegalStateException("missing featureplant cursor migration");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
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
        private int executeQueryCalls;
        private int executeUpdateCalls;
        private int connectionCloseCalls;
        private int preparedStatementCloseCalls;
        private int resultSetCloseCalls;
        private boolean failExecuteQuery;

        private RecordingJdbc(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        private Connection openConnection() {
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                this::handleConnectionInvocation
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

        private Object handlePreparedStatementInvocation(Object proxy, Method method, Object[] args)
            throws SQLException {
            return switch (method.getName()) {
                case "setString", "setLong" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "executeQuery" -> {
                    executeQueryCalls++;
                    if (failExecuteQuery) {
                        throw new SQLException("query failed");
                    }
                    yield resultSet();
                }
                case "executeUpdate" -> {
                    executeUpdateCalls++;
                    yield 1;
                }
                case "close" -> {
                    preparedStatementCloseCalls++;
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet() {
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                this::handleResultSetInvocation
            );
        }

        private Object handleResultSetInvocation(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> {
                    rowIndex++;
                    yield rowIndex < rows.size();
                }
                case "getObject" -> rows.get(rowIndex).get((String) args[0]);
                case "close" -> {
                    resultSetCloseCalls++;
                    yield null;
                }
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
            return 0;
        }
    }
}
