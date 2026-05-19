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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMarketMetadataStoreTest {
    @Test
    void migrationCreatesMarketMetadataTableAndIndexes() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("create table if not exists market_metadata"));
        assertTrue(sql.contains("market_ticker text primary key"));
        assertTrue(sql.contains("event_ticker text"));
        assertTrue(sql.contains("series_ticker text"));
        assertTrue(sql.contains("status text"));
        assertTrue(sql.contains("open_time timestamptz"));
        assertTrue(sql.contains("close_time timestamptz"));
        assertTrue(sql.contains("settlement_time timestamptz"));
        assertTrue(sql.contains("rules_payload jsonb"));
        assertTrue(sql.contains("market_payload jsonb not null"));
        assertTrue(sql.contains("updated_at timestamptz not null default now()"));
        assertTrue(sql.contains("market_metadata_event_ticker_idx"));
        assertTrue(sql.contains("market_metadata_series_status_idx"));
        assertTrue(sql.contains("market_metadata_status_close_time_idx"));
        assertTrue(sql.contains("market_metadata_updated_at_idx"));
    }

    @Test
    void upsertSqlUsesJsonbCastsAndConflictUpdate() {
        String sql = JdbcMarketMetadataStore.UPSERT_SQL.toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("insert into market_metadata"));
        assertTrue(sql.contains("values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)"));
        assertTrue(sql.contains("on conflict (market_ticker) do update set"));
        assertTrue(sql.contains("event_ticker = excluded.event_ticker"));
        assertTrue(sql.contains("rules_payload = excluded.rules_payload"));
        assertTrue(sql.contains("market_payload = excluded.market_payload"));
        assertTrue(sql.contains("updated_at = now()"));
    }

    @Test
    void upsertBindsNullableFieldsAndJsonPayloads() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcMarketMetadataStore store = new JdbcMarketMetadataStore(jdbc::openConnection);
        MarketMetadata metadata = new MarketMetadata(
            "MARKET-1",
            "EVENT-1",
            "SERIES-1",
            "open",
            Instant.parse("2026-05-19T01:00:00Z"),
            Instant.parse("2026-05-20T02:00:00Z"),
            null,
            null,
            "{\"ticker\":\"MARKET-1\"}"
        );

        store.upsertMarketMetadata(metadata);

        assertEquals(1, jdbc.openConnections);
        assertEquals(1, jdbc.executeUpdateCalls);
        assertEquals(JdbcMarketMetadataStore.UPSERT_SQL, jdbc.preparedSql);
        assertEquals("MARKET-1", jdbc.parameters.get(1));
        assertEquals("EVENT-1", jdbc.parameters.get(2));
        assertEquals("SERIES-1", jdbc.parameters.get(3));
        assertEquals("open", jdbc.parameters.get(4));
        assertInstanceOf(OffsetDateTime.class, jdbc.parameters.get(5));
        assertEquals("2026-05-19T01:00Z", jdbc.parameters.get(5).toString());
        assertInstanceOf(OffsetDateTime.class, jdbc.parameters.get(6));
        assertEquals("2026-05-20T02:00Z", jdbc.parameters.get(6).toString());
        assertEquals(new SqlNull(Types.TIMESTAMP_WITH_TIMEZONE), jdbc.parameters.get(7));
        assertEquals(new SqlNull(Types.VARCHAR), jdbc.parameters.get(8));
        assertEquals("{\"ticker\":\"MARKET-1\"}", jdbc.parameters.get(9));
    }

    @Test
    void upsertBindsRulesAndSettlementWhenPresent() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcMarketMetadataStore store = new JdbcMarketMetadataStore(jdbc::openConnection);
        MarketMetadata metadata = new MarketMetadata(
            "MARKET-2",
            null,
            null,
            null,
            null,
            null,
            Instant.parse("2026-05-21T03:00:00Z"),
            "{\"rule\":\"value\"}",
            "{\"ticker\":\"MARKET-2\"}"
        );

        store.upsertMarketMetadata(metadata);

        assertEquals(null, jdbc.parameters.get(2));
        assertEquals(null, jdbc.parameters.get(3));
        assertEquals(null, jdbc.parameters.get(4));
        assertEquals(new SqlNull(Types.TIMESTAMP_WITH_TIMEZONE), jdbc.parameters.get(5));
        assertEquals(new SqlNull(Types.TIMESTAMP_WITH_TIMEZONE), jdbc.parameters.get(6));
        assertInstanceOf(OffsetDateTime.class, jdbc.parameters.get(7));
        assertEquals("2026-05-21T03:00Z", jdbc.parameters.get(7).toString());
        assertEquals("{\"rule\":\"value\"}", jdbc.parameters.get(8));
        assertEquals("{\"ticker\":\"MARKET-2\"}", jdbc.parameters.get(9));
    }

    @Test
    void recordValidationRejectsBlankTickerAndNullMarketPayload() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new MarketMetadata(" ", null, null, null, null, null, null, null, "{}")
        );
        assertThrows(
            NullPointerException.class,
            () -> new MarketMetadata("MARKET-1", null, null, null, null, null, null, null, null)
        );
    }

    private static String migrationSql() throws Exception {
        String resource = "db/migration/V007__market_metadata.sql";
        try (InputStream inputStream = JdbcMarketMetadataStoreTest.class.getClassLoader()
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
                case "setString", "setObject" -> {
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
