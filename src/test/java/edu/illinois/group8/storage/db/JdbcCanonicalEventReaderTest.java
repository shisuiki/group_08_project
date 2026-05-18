package edu.illinois.group8.storage.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JdbcCanonicalEventReaderTest {
    @Test
    void implementsCanonicalDbEventReaderContract() {
        JdbcCanonicalEventReader reader = new JdbcCanonicalEventReader(() -> {
            throw new SQLException("unused");
        });

        assertInstanceOf(CanonicalDbEventReader.class, reader);
    }

    @Test
    void defaultReadUsesCommitCursorOrderExcludesReplayAndMapsRows() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "canonical_commit_seq", 43L,
            "event_id", "event-1",
            "raw_event_id", "raw-1",
            "replay_id", null,
            "stream_name", "canonical.trade",
            "event_type", "market_trade",
            "schema_version", 1,
            "market_ticker", "MARKET-1",
            "event_ts_ms", 1000L,
            "ingest_ts_ns", 2000L,
            "publish_ts_ns", 3000L,
            "payload", "{\"event_id\":\"event-1\"}"
        )));
        JdbcCanonicalEventReader reader = new JdbcCanonicalEventReader(jdbc::openConnection);

        List<CanonicalDbReadEvent> events = reader.read(
            new CanonicalDbReadRequest(new CanonicalDbCursor(42L), null, null, null, false, 0)
        );

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("from canonical_events"));
        assertTrue(sql.contains("canonical_commit_seq > ?"));
        assertTrue(sql.contains("replay_id is null"));
        assertTrue(sql.contains("order by canonical_commit_seq asc"));
        assertFalse(sql.contains(" limit ?"));
        assertEquals(List.of(42L), jdbc.bindings);

        assertEquals(1, events.size());
        CanonicalDbReadEvent event = events.get(0);
        assertEquals(43L, event.canonicalCommitSeq());
        assertEquals(new CanonicalDbCursor(43L), event.nextCursor());
        assertEquals("event-1", event.eventId());
        assertEquals("raw-1", event.rawEventId());
        assertEquals("canonical.trade", event.streamName());
        assertEquals("market_trade", event.eventType());
        assertEquals(1, event.schemaVersion());
        assertEquals("MARKET-1", event.marketTicker());
        assertEquals(1000L, event.eventTsMs());
        assertEquals(2000L, event.ingestTsNs());
        assertEquals(3000L, event.publishTsNs());
        assertEquals("{\"event_id\":\"event-1\"}", event.payload());
        assertEquals(event.eventId(), event.canonicalDbEvent().eventId());
        assertEquals(1, jdbc.resultSetCloseCalls);
        assertEquals(1, jdbc.preparedStatementCloseCalls);
        assertEquals(1, jdbc.connectionCloseCalls);
    }

    @Test
    void filtersReplayIdAndLimitBindInStableOrder() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcCanonicalEventReader reader = new JdbcCanonicalEventReader(jdbc::openConnection);

        reader.read(new CanonicalDbReadRequest(
            new CanonicalDbCursor(10L),
            List.of("canonical.trade", "canonical.ticker"),
            List.of("MARKET-1", "MARKET-2"),
            "replay-7",
            false,
            25
        ));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("stream_name in (?, ?)"));
        assertTrue(sql.contains("market_ticker in (?, ?)"));
        assertTrue(sql.contains("replay_id = ?"));
        assertFalse(sql.contains("replay_id is null"));
        assertTrue(sql.contains("limit ?"));
        assertEquals(List.of(
            10L,
            "canonical.trade",
            "canonical.ticker",
            "MARKET-1",
            "MARKET-2",
            "replay-7",
            25
        ), jdbc.bindings);
    }

    @Test
    void includeReplayEventsOmitsDefaultReplayFilter() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcCanonicalEventReader reader = new JdbcCanonicalEventReader(jdbc::openConnection);

        reader.read(new CanonicalDbReadRequest(new CanonicalDbCursor(5L), List.of(), List.of(), null, true, -1));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertFalse(sql.contains("replay_id is null"));
        assertFalse(sql.contains("replay_id = ?"));
        assertFalse(sql.contains("limit ?"));
        assertEquals(List.of(5L), jdbc.bindings);
    }

    @Test
    void nullRequestStartsAtZeroAndExcludesReplayRows() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcCanonicalEventReader reader = new JdbcCanonicalEventReader(jdbc::openConnection);

        reader.read(null);

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("replay_id is null"));
        assertEquals(List.of(0L), jdbc.bindings);
    }

    @Test
    void readWrapsSqlFailureWithTableContext() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        jdbc.failExecuteQuery = true;
        JdbcCanonicalEventReader reader = new JdbcCanonicalEventReader(jdbc::openConnection);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> reader.read(CanonicalDbReadRequest.fromStart())
        );

        assertTrue(thrown.getMessage().contains("canonical_events"));
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
