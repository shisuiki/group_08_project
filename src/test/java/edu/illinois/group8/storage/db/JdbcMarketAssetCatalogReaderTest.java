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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMarketAssetCatalogReaderTest {
    @Test
    void implementsMarketMetadataReaderContract() {
        JdbcMarketAssetCatalogReader reader = new JdbcMarketAssetCatalogReader(() -> {
            throw new SQLException("unused");
        });

        assertInstanceOf(MarketMetadataReader.class, reader);
    }

    @Test
    void readsMergedMetadataAndFeatureOutputCatalog() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "market_ticker", "KX-ASSET-1",
            "event_ticker", null,
            "series_ticker", null,
            "status", "indexed",
            "open_time", null,
            "close_time", Instant.parse("2026-05-20T02:00:00Z"),
            "settlement_time", null,
            "rules_payload", "{}",
            "market_payload", "{}"
        )));
        JdbcMarketAssetCatalogReader reader = new JdbcMarketAssetCatalogReader(jdbc::openConnection);

        List<MarketMetadata> rows = reader.read(MarketMetadataReadRequest.search(null, null, 50));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("select distinct market_ticker"));
        assertTrue(sql.contains("from feature_outputs"));
        assertTrue(sql.contains("full join market_metadata"));
        assertTrue(sql.contains("coalesce(mm.status, 'indexed')"));
        assertTrue(sql.contains("limit ?"));
        assertEquals(List.of(50), jdbc.bindings);
        assertEquals(1, rows.size());
        assertEquals("KX-ASSET-1", rows.get(0).marketTicker());
        assertEquals("indexed", rows.get(0).status());
        assertEquals("{}", rows.get(0).marketPayload());
        assertEquals(Instant.parse("2026-05-20T02:00:00Z"), rows.get(0).closeTime());
    }

    @Test
    void bindsFiltersInStableOrder() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcMarketAssetCatalogReader reader = new JdbcMarketAssetCatalogReader(jdbc::openConnection);

        reader.read(MarketMetadataReadRequest.searchWithoutGenerated("SERIES", "open", 25, "v1"));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("mm.series_ticker = ?"));
        assertTrue(sql.contains("coalesce(mm.status, 'indexed') = ?"));
        assertTrue(sql.contains("from market_semantic_metadata smm"));
        assertEquals(List.of("SERIES", "open", "v1", 25), jdbc.bindings);
    }

    @Test
    void wrapsSqlFailureWithCatalogContext() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        jdbc.failExecuteQuery = true;
        JdbcMarketAssetCatalogReader reader = new JdbcMarketAssetCatalogReader(jdbc::openConnection);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> reader.read(MarketMetadataReadRequest.defaultSearch())
        );

        assertTrue(thrown.getMessage().contains("market asset catalog"));
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
        private int rowIndex = -1;

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

        private Object handlePreparedStatementInvocation(Object proxy, Method method, Object[] args)
            throws SQLException {
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
                        throw new SQLException("query failed");
                    }
                    yield resultSet();
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet() {
            InvocationHandler handler = this::handleResultSetInvocation;
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                handler
            );
        }

        private Object handleResultSetInvocation(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> {
                    rowIndex++;
                    yield rowIndex < rows.size();
                }
                case "getString" -> rows.get(rowIndex).get((String) args[0]);
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
