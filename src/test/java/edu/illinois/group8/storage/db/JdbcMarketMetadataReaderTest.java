package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMarketMetadataReaderTest {
    @Test
    void implementsMarketMetadataReaderContract() {
        JdbcMarketMetadataReader reader = new JdbcMarketMetadataReader(() -> {
            throw new SQLException("unused");
        });

        assertInstanceOf(MarketMetadataReader.class, reader);
    }

    @Test
    void defaultReadIsBoundedOrderedAndMapsRows() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "market_ticker", "MARKET-1",
            "event_ticker", "EVENT-1",
            "series_ticker", "SERIES-1",
            "status", "open",
            "open_time", OffsetDateTime.ofInstant(Instant.parse("2026-05-19T01:00:00Z"), ZoneOffset.UTC),
            "close_time", Instant.parse("2026-05-20T02:00:00Z"),
            "settlement_time", null,
            "rules_payload", "{\"rule\":\"value\"}",
            "market_payload", "{\"ticker\":\"MARKET-1\"}"
        )));
        JdbcMarketMetadataReader reader = new JdbcMarketMetadataReader(jdbc::openConnection);

        List<MarketMetadata> rows = reader.read(null);

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("from market_metadata"));
        assertTrue(sql.contains("where 1 = 1"));
        assertTrue(sql.contains("order by series_ticker asc nulls last, market_ticker asc"));
        assertTrue(sql.contains("limit ?"));
        assertFalse(sql.contains("market_ticker = ?"));
        assertEquals(List.of(MarketMetadataReadRequest.DEFAULT_MAX_ROWS), jdbc.bindings);

        assertEquals(1, rows.size());
        MarketMetadata row = rows.get(0);
        assertEquals("MARKET-1", row.marketTicker());
        assertEquals("EVENT-1", row.eventTicker());
        assertEquals("SERIES-1", row.seriesTicker());
        assertEquals("open", row.status());
        assertEquals(Instant.parse("2026-05-19T01:00:00Z"), row.openTime());
        assertEquals(Instant.parse("2026-05-20T02:00:00Z"), row.closeTime());
        assertEquals(null, row.settlementTime());
        assertEquals("{\"rule\":\"value\"}", row.rulesPayload());
        assertEquals("{\"ticker\":\"MARKET-1\"}", row.marketPayload());
        assertEquals(1, jdbc.resultSetCloseCalls);
        assertEquals(1, jdbc.preparedStatementCloseCalls);
        assertEquals(1, jdbc.connectionCloseCalls);
    }

    @Test
    void exactTickerFilterBindsTickerAndSingleRowLimit() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcMarketMetadataReader reader = new JdbcMarketMetadataReader(jdbc::openConnection);

        List<MarketMetadata> rows = reader.read(MarketMetadataReadRequest.byTicker(" MARKET-1 "));

        assertEquals(List.of(), rows);
        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("market_ticker = ?"));
        assertFalse(sql.contains("series_ticker = ?"));
        assertFalse(sql.contains("status = ?"));
        assertEquals(List.of("MARKET-1", 1), jdbc.bindings);
    }

    @Test
    void seriesStatusFiltersBindInStableOrderAndClampLimit() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcMarketMetadataReader reader = new JdbcMarketMetadataReader(jdbc::openConnection);

        reader.read(MarketMetadataReadRequest.search("SERIES-1", "open", 50_000));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("series_ticker = ?"));
        assertTrue(sql.contains("status = ?"));
        assertTrue(sql.contains("limit ?"));
        assertEquals(List.of("SERIES-1", "open", MarketMetadataReadRequest.MAX_ROWS), jdbc.bindings);
    }

    @Test
    void searchWithoutGeneratedFiltersGeneratedSemanticRowsByTaxonomy() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcMarketMetadataReader reader = new JdbcMarketMetadataReader(jdbc::openConnection);

        reader.read(MarketMetadataReadRequest.searchWithoutGenerated("SERIES-1", "open", 5, " tax-v1 "));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("not exists"));
        assertTrue(sql.contains("from market_semantic_metadata smm"));
        assertTrue(sql.contains("smm.status = 'generated'"));
        assertEquals(List.of("SERIES-1", "open", "tax-v1", 5), jdbc.bindings);
    }

    @Test
    void requestNormalizesBlankFiltersAndRejectsInvalidLimit() {
        MarketMetadataReadRequest request = new MarketMetadataReadRequest(" ", " SERIES-1 ", " ", 10, " ");

        assertEquals(null, request.marketTicker());
        assertEquals("SERIES-1", request.seriesTicker());
        assertEquals(null, request.status());
        assertEquals(10, request.maxRows());
        assertThrows(
            IllegalArgumentException.class,
            () -> new MarketMetadataReadRequest(null, null, null, 0, null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MarketMetadataReadRequest.byTicker(" ")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MarketMetadataReadRequest.searchWithoutGenerated(null, null, 10, " ")
        );
    }

    @Test
    void sqlFailureWrapsWithTableContext() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        jdbc.failExecuteQuery = true;
        JdbcMarketMetadataReader reader = new JdbcMarketMetadataReader(jdbc::openConnection);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> reader.read(MarketMetadataReadRequest.defaultSearch())
        );

        assertTrue(thrown.getMessage().contains("market_metadata"));
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
                case "getObject" -> objectValue((String) args[0]);
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

        private Object objectValue(String column) {
            return rows.get(rowIndex).get(column);
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
