package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.HashMap;
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
        assertEquals(1, jdbc.executeUpdateCalls);
        assertEquals(JdbcFeatureOutputStore.INSERT_SQL, jdbc.preparedSql);
        assertEquals("feature-event-1", jdbc.parameters.get(1));
        assertEquals(null, jdbc.parameters.get(2));
        assertEquals("feature.bbo", jdbc.parameters.get(3));
        assertEquals(2, jdbc.parameters.get(4));
        assertEquals(null, jdbc.parameters.get(5));
        assertEquals(new SqlNull(Types.BIGINT), jdbc.parameters.get(6));
        assertEquals("{\"midpoint_micros\":123}", jdbc.parameters.get(7));
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

        assertEquals("canonical-1", jdbc.parameters.get(2));
        assertEquals("MARKET-1", jdbc.parameters.get(5));
        assertEquals(123456L, jdbc.parameters.get(6));
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

        private Object handleConnectionInvocation(Object proxy, Method method, Object[] args) {
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
