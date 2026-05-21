package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
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

class JdbcSemanticMarketMetadataReaderTest {
    @Test
    void implementsSemanticMarketMetadataReaderContract() {
        JdbcSemanticMarketMetadataReader reader = new JdbcSemanticMarketMetadataReader(() -> {
            throw new SQLException("unused");
        });

        assertInstanceOf(SemanticMarketMetadataReader.class, reader);
    }

    @Test
    void defaultReadIsBoundedJoinedAndDoesNotSelectRawResponse() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcSemanticMarketMetadataReader reader = new JdbcSemanticMarketMetadataReader(jdbc::openConnection);

        List<SemanticMarketMetadataRow> rows = reader.read(
            SemanticMarketMetadataReadRequest.defaultForTaxonomy(" tax-v1 ")
        );

        assertEquals(List.of(), rows);
        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("from market_semantic_metadata smm"));
        assertTrue(sql.contains("left join market_metadata mm"));
        assertTrue(sql.contains("left join latest_market_state lms"));
        assertTrue(sql.contains("left join market_feature_stats mfs"));
        assertTrue(sql.contains("left join lateral"));
        assertTrue(sql.contains("from feature_outputs fo"));
        assertTrue(sql.contains("feature.bbo"));
        assertTrue(sql.contains("fo.event_ts_ms >= lms.last_event_ts_ms - 86400000"));
        assertTrue(sql.contains("fo.event_ts_ms <= lms.last_event_ts_ms"));
        assertTrue(sql.contains("order by fo.event_ts_ms asc"));
        assertTrue(sql.contains("regexp_replace(smm.market_ticker"));
        assertTrue(sql.contains("aggregate_open_interest"));
        assertTrue(sql.contains("price_change_24h_micros"));
        assertTrue(sql.contains("coalesce(mfs.display_eligible, false)"));
        assertTrue(sql.contains("tags::text as tags"));
        assertTrue(sql.contains("lms.last_canonical_commit_seq"));
        assertTrue(sql.contains("nullif(lms.open_interest, 0)"));
        assertTrue(sql.contains("open_interest_fp"));
        assertTrue(sql.contains("last_canonical_commit_seq desc nulls last"));
        assertTrue(sql.contains("limit ?"));
        assertFalse(sql.contains("raw_response"));
        assertEquals(List.of("tax-v1", SemanticMarketMetadataReadRequest.DEFAULT_MAX_ROWS), jdbc.bindings);
    }

    @Test
    void filtersBindInStableOrderAndClampLimit() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcSemanticMarketMetadataReader reader = new JdbcSemanticMarketMetadataReader(jdbc::openConnection);

        reader.read(new SemanticMarketMetadataReadRequest(
            "tax-v1",
            " MKT-1 ",
            " generated ",
            " open ",
            "election",
            " Midterm ",
            50_000
        ));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("smm.market_ticker = ?"));
        assertTrue(sql.contains("smm.status = ?"));
        assertTrue(sql.contains("mm.status = ?"));
        assertTrue(sql.contains("jsonb_array_elements_text(smm.tags)"));
        assertTrue(sql.contains("lower(smm.market_ticker) like ?"));
        assertTrue(sql.contains("limit ?"));
        assertEquals(List.of(
            "tax-v1",
            "MKT-1",
            "generated",
            "open",
            "election",
            "%midterm%",
            "%midterm%",
            "%midterm%",
            "%midterm%",
            SemanticMarketMetadataReadRequest.MAX_ROWS
        ), jdbc.bindings);
    }

    @Test
    void mapsRowsAndParsesTagsAndLatestQuote() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "market_ticker", "MKT-1",
            "base_market_key", "MKT",
            "side_tag", "1",
            "event_ticker", "EVENT-1",
            "series_ticker", "SERIES-1",
            "market_status", "open",
            "market_title", "Will it rain?",
            "taxonomy_version", "tax-v1",
            "model", "model-a",
            "prompt_version", "prompt-v1",
            "semantic_status", "generated",
            "sector", "weather",
            "subsector", "rain",
            "event_type", "forecast",
            "region", "us",
            "time_horizon", "daily",
            "liquidity_bucket", "high",
            "risk_bucket", "low",
            "tags", "[\"weather\",\"rain\"]",
            "confidence", new BigDecimal("0.83"),
            "rationale", "public forecast market",
            "generated_at", OffsetDateTime.ofInstant(Instant.parse("2026-05-20T00:00:00Z"), ZoneOffset.UTC),
            "updated_at", Instant.parse("2026-05-20T00:00:02Z"),
            "generated_age_ms", 30L,
            "updated_age_ms", 20L,
            "last_event_ts_ms", 1_000L,
            "last_canonical_event_id", "event-1",
            "last_canonical_commit_seq", 42L,
            "best_bid_micros", 440_000L,
            "best_ask_micros", 460_000L,
            "midpoint_micros", 450_000L,
            "open_interest", 123L,
            "aggregate_open_interest", 223L,
            "current_midpoint_micros", 450_000L,
            "midpoint_24h_ago_micros", 430_000L,
            "price_change_24h_micros", 20_000L,
            "latest_state_updated_at", Instant.parse("2026-05-20T00:00:03Z"),
            "latest_state_age_ms", 10L
        )));
        JdbcSemanticMarketMetadataReader reader = new JdbcSemanticMarketMetadataReader(jdbc::openConnection);

        List<SemanticMarketMetadataRow> rows = reader.read(
            SemanticMarketMetadataReadRequest.defaultForTaxonomy("tax-v1")
        );

        assertEquals(1, rows.size());
        SemanticMarketMetadataRow row = rows.get(0);
        assertEquals("MKT-1", row.marketTicker());
        assertEquals("MKT", row.baseMarketKey());
        assertEquals("1", row.sideTag());
        assertEquals("EVENT-1", row.eventTicker());
        assertEquals("SERIES-1", row.seriesTicker());
        assertEquals("open", row.marketStatus());
        assertEquals("Will it rain?", row.marketTitle());
        assertEquals("weather", row.sector());
        assertEquals(List.of("weather", "rain"), row.tags());
        assertEquals(new BigDecimal("0.83"), row.confidence());
        assertEquals(42L, row.lastCanonicalCommitSeq());
        assertEquals(450_000L, row.midpointMicros());
        assertEquals(223L, row.aggregateOpenInterest());
        assertEquals(430_000L, row.midpoint24hAgoMicros());
        assertEquals(20_000L, row.priceChange24hMicros());
        assertEquals(Instant.parse("2026-05-20T00:00:03Z"), row.latestStateUpdatedAt());
    }

    @Test
    void malformedTagsFallBackToSafeString() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "market_ticker", "MKT-1",
            "taxonomy_version", "tax-v1",
            "semantic_status", "generated",
            "tags", "not-json"
        )));
        JdbcSemanticMarketMetadataReader reader = new JdbcSemanticMarketMetadataReader(jdbc::openConnection);

        List<SemanticMarketMetadataRow> rows = reader.read(
            SemanticMarketMetadataReadRequest.defaultForTaxonomy("tax-v1")
        );

        assertEquals(List.of("not-json"), rows.get(0).tags());
    }

    @Test
    void requestNormalizesBlankFiltersAndRejectsInvalidLimit() {
        SemanticMarketMetadataReadRequest request = new SemanticMarketMetadataReadRequest(
            " tax ",
            " ",
            " generated ",
            " ",
            " tag ",
            " ",
            10
        );

        assertEquals("tax", request.taxonomyVersion());
        assertEquals(null, request.marketTicker());
        assertEquals("generated", request.semanticStatus());
        assertEquals(null, request.marketStatus());
        assertEquals("tag", request.tag());
        assertEquals(null, request.query());
        assertThrows(
            IllegalArgumentException.class,
            () -> new SemanticMarketMetadataReadRequest(" ", null, null, null, null, null, 10)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new SemanticMarketMetadataReadRequest("tax", null, null, null, null, null, 0)
        );
    }

    @Test
    void sqlFailureWrapsWithTableContext() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        jdbc.failExecuteQuery = true;
        JdbcSemanticMarketMetadataReader reader = new JdbcSemanticMarketMetadataReader(jdbc::openConnection);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> reader.read(SemanticMarketMetadataReadRequest.defaultForTaxonomy("tax-v1"))
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
        private final List<Object> bindings = new ArrayList<>();
        private String preparedSql;
        private boolean failExecuteQuery;

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
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet() {
            InvocationHandler handler = new ResultSetHandler(rows);
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                handler
            );
        }
    }

    private static final class ResultSetHandler implements InvocationHandler {
        private final List<Map<String, Object>> rows;
        private int rowIndex = -1;

        private ResultSetHandler(List<Map<String, Object>> rows) {
            this.rows = rows;
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
                case "getBigDecimal" -> bigDecimalValue((String) args[0]);
                case "close" -> null;
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

        private BigDecimal bigDecimalValue(String column) {
            Object value = objectValue(column);
            if (value == null) {
                return null;
            }
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            return new BigDecimal(value.toString());
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Double.TYPE) {
            return 0D;
        }
        if (returnType == Float.TYPE) {
            return 0F;
        }
        return null;
    }
}
