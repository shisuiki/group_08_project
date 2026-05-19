package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcLatestMarketStateStoreTest {
    @Test
    void migrationCreatesLatestMarketStateTableAndIndexes() throws Exception {
        String migration = migrationSql();
        String sql = migration.toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("create table if not exists latest_market_state"));
        assertTrue(sql.contains("market_ticker text primary key"));
        assertTrue(sql.contains("last_event_ts_ms bigint"));
        assertTrue(sql.contains("last_canonical_event_id text"));
        assertTrue(sql.contains("best_bid_micros bigint"));
        assertTrue(sql.contains("best_ask_micros bigint"));
        assertTrue(sql.contains("midpoint_micros bigint"));
        assertTrue(sql.contains("open_interest bigint"));
        assertTrue(sql.contains("payload jsonb"));
        assertTrue(sql.contains("updated_at timestamptz not null default now()"));
        assertTrue(sql.contains("latest_market_state_last_event_ts_ms_idx"));
        assertTrue(sql.contains("latest_market_state_updated_at_idx"));
    }

    @Test
    void upsertSqlUsesJsonbPayloadAndTimestampFence() {
        String sql = JdbcLatestMarketStateStore.UPSERT_SQL.toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("insert into latest_market_state"));
        assertTrue(sql.contains("values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)"));
        assertTrue(sql.contains("on conflict (market_ticker) do update"));
        assertTrue(sql.contains("where latest_market_state.last_event_ts_ms is null"));
        assertTrue(sql.contains("excluded.last_event_ts_ms is not null"));
        assertTrue(sql.contains("excluded.last_event_ts_ms >= latest_market_state.last_event_ts_ms"));
        assertTrue(sql.contains("updated_at = now()"));
    }

    @Test
    void upsertBindsNullableFieldsAndPayloadThenExecutes() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcLatestMarketStateStore store = new JdbcLatestMarketStateStore(jdbc::openConnection);
        LatestMarketState state = new LatestMarketState(
            "MARKET-1",
            123L,
            "event-1",
            null,
            456L,
            null,
            789L,
            null
        );

        store.upsertLatestMarketState(state);

        assertEquals(1, jdbc.openConnections);
        assertEquals(1, jdbc.executeUpdateCalls);
        assertEquals(JdbcLatestMarketStateStore.UPSERT_SQL, jdbc.preparedSql);
        assertEquals("MARKET-1", jdbc.parameters.get(1));
        assertEquals(123L, jdbc.parameters.get(2));
        assertEquals("event-1", jdbc.parameters.get(3));
        assertEquals(new SqlNull(Types.BIGINT), jdbc.parameters.get(4));
        assertEquals(456L, jdbc.parameters.get(5));
        assertEquals(new SqlNull(Types.BIGINT), jdbc.parameters.get(6));
        assertEquals(789L, jdbc.parameters.get(7));
        assertEquals(new SqlNull(Types.VARCHAR), jdbc.parameters.get(8));
    }

    @Test
    void upsertBindsJsonPayloadString() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcLatestMarketStateStore store = new JdbcLatestMarketStateStore(jdbc::openConnection);
        LatestMarketState state = new LatestMarketState(
            "MARKET-2",
            124L,
            "event-2",
            100L,
            200L,
            150L,
            null,
            "{\"market_ticker\":\"MARKET-2\"}"
        );

        store.upsertLatestMarketState(state);

        assertEquals("{\"market_ticker\":\"MARKET-2\"}", jdbc.parameters.get(8));
    }

    @Test
    void blankMarketTickerIsRejectedBeforeStoreUse() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new LatestMarketState(" ", 1L, null, null, null, null, null, null)
        );
    }

    private static String migrationSql() throws Exception {
        String resource = "db/migration/V005__latest_market_state.sql";
        try (InputStream inputStream = JdbcLatestMarketStateStoreTest.class.getClassLoader()
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
        private String preparedSql;
        private final Map<Integer, Object> parameters = new HashMap<>();

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
                case "prepareStatement" -> {
                    preparedSql = (String) args[0];
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

        private Object handlePreparedStatementInvocation(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "setString", "setLong" -> {
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
