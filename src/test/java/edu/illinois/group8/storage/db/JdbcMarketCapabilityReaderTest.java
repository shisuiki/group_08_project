package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMarketCapabilityReaderTest {
    @Test
    void implementsReaderContract() {
        JdbcMarketCapabilityReader reader = new JdbcMarketCapabilityReader(() -> {
            throw new SQLException("unused");
        });

        assertInstanceOf(MarketCapabilityReader.class, reader);
    }

    @Test
    void aggregatesUnifiedUniverseAndMapsSourceAwareChartability() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(
            List.of(row(
                "total_assets", 138_035L,
                "chartable_count", 25_000L,
                "quote_count", 50L,
                "stale_quote_count", 47L,
                "semantic_generated_count", 10_000L,
                "semantic_review_required_count", 20L,
                "semantic_failed_count", 3L,
                "semantic_rate_limited_count", 2L,
                "semantic_missing_count", 128_010L,
                "metadata_only_count", 100_000L,
                "display_eligible_count", 120L,
                "display_ineligible_count", 137_915L,
                "semantic_eligible_generated_count", 100L,
                "semantic_eligible_missing_count", 20L
            )),
            List.of(row(
                "filtered_count", 21_590L,
                "market_ticker", "MKT-TRADE",
                "event_ticker", "EVENT-1",
                "series_ticker", "SERIES-1",
                "status", "indexed",
                "catalog_source", "market_metadata",
                "has_latest_state", true,
                "has_quote", true,
                "quote_event_ts_ms", 1_234_000L,
                "quote_age_ms", 20_000L,
                "quote_status", "stale_quote",
                "has_bbo_history", false,
                "chartable_from_bbo", false,
                "chartable_from_ticker_snapshot", false,
                "chartable_from_trade_tape", true,
                "best_chart_source", "trade_tape",
                "chartable_1h", false,
                "chartable_24h", true,
                "chartable", true,
                "chart_status", "chartable_24h",
                "chart_reason", "trade_tape_history_available",
                "semantic_status", "generated",
                "semantic_sector", "finance",
                "semantic_subsector", "macro",
                "semantic_event_type", "rate",
                "feature_count", 12L,
                "bbo_sample_count", 0L,
                "trade_sample_count", 12L,
                "ticker_sample_count", 0L,
                "history_bars_24h_count", 12L,
                "trade_24h_count", 12L,
                "quote_24h_count", 0L,
                "last_event_ts_ms", 1_234_000L,
                "liquidity_rank", 3L,
                "display_eligible", true
            ))
        ));
        JdbcMarketCapabilityReader reader = new JdbcMarketCapabilityReader(jdbc::openConnection);

        MarketCapabilityPage page = reader.readPage(new MarketCapabilityReadRequest(
            "rate",
            "indexed",
            "chart_ready",
            50,
            100,
            "v1",
            false,
            false
        ));

        String summarySql = jdbc.preparedSqls.get(0).toLowerCase(Locale.ROOT);
        String pageSql = jdbc.preparedSqls.get(1).toLowerCase(Locale.ROOT);
        assertTrue(pageSql.contains("from market_feature_stats"));
        assertTrue(pageSql.contains("full join market_metadata"));
        assertTrue(pageSql.contains("left join latest_market_state"));
        assertTrue(pageSql.contains("left join market_semantic_metadata"));
        assertTrue(pageSql.contains("coalesce(mfs.ticker_chart_count, 0) > 0"));
        assertTrue(pageSql.contains("coalesce(mfs.trade_chart_count, 0) > 0"));
        assertTrue(pageSql.contains("mfs.last_chart_ts_ms"));
        assertTrue(pageSql.contains("market_feature_stats mfs"));
        assertEquals(-1, pageSql.indexOf("from feature_outputs"));
        assertTrue(pageSql.contains("best_chart_source"));
        assertTrue(pageSql.contains("and display_eligible"));
        assertTrue(summarySql.contains("stale_quote_count"));
        assertTrue(summarySql.contains("semantic_rate_limited_count"));
        assertEquals(List.of(
            JdbcMarketCapabilityReader.LIVE_QUOTE_STALE_AFTER_MS,
            "v1",
            "%rate%", "%rate%", "%rate%", "%rate%", "%rate%", "%rate%", "%rate%",
            "indexed"
        ), jdbc.bindingsByStatement.get(0));
        assertEquals(List.of(
            JdbcMarketCapabilityReader.LIVE_QUOTE_STALE_AFTER_MS,
            "v1",
            "%rate%", "%rate%", "%rate%", "%rate%", "%rate%", "%rate%", "%rate%",
            "indexed",
            100,
            50
        ), jdbc.bindingsByStatement.get(1));

        assertEquals(138_035L, page.summary().totalAssets());
        assertEquals(47L, page.summary().staleQuoteCount());
        assertEquals(2L, page.summary().semanticRateLimitedCount());
        assertEquals(120L, page.summary().displayEligibleCount());
        assertEquals(20L, page.summary().semanticEligibleMissingCount());
        assertEquals(21_590L, page.totalCount());
        assertEquals(100, page.offset());
        assertEquals(50, page.limit());
        assertEquals(1, page.markets().size());
        MarketCapability capability = page.markets().get(0);
        assertEquals("MKT-TRADE", capability.marketTicker());
        assertTrue(capability.chartable());
        assertEquals("trade_tape", capability.bestChartSource());
        assertTrue(capability.chartableFromTradeTape());
        assertEquals("trade_tape_history_available", capability.chartReason());
        assertTrue(capability.displayEligible());
        assertEquals(12L, capability.historyBars24hCount());
        assertEquals(3L, capability.liquidityRank());
    }

    @Test
    void supportsQuoteAndSemanticCapabilityFilters() {
        assertSqlFilter("chart_ready", "and display_eligible");
        assertSqlFilter("quote_available", "and has_quote");
        assertSqlFilter("quote_only", "and has_quote and not chartable");
        assertSqlFilter("quote_stale", "quote_status = 'stale_quote'");
        assertSqlFilter("metadata_only", "and not chartable");
        assertSqlFilter("semantic_tagged", "semantic_status <> 'missing'");
        assertSqlFilter("unclassified", "semantic_status = 'missing'");
    }

    @Test
    void capabilityFiltersDoNotJoinPredicateTokensWithOrderBy() {
        for (String filter : List.of(
            "chart_ready",
            "quote_available",
            "quote_only",
            "quote_stale",
            "metadata_only",
            "semantic_tagged",
            "unclassified"
        )) {
            List<Object> bindings = new ArrayList<>();
            String sql = JdbcMarketCapabilityReader.pageSql(new MarketCapabilityReadRequest(
                null,
                null,
                filter,
                5,
                0,
                "v1",
                false,
                false
            ), bindings).toLowerCase(Locale.ROOT);
            String normalizedSql = sql.replaceAll("\\s+", " ");

            assertTrue(sql.contains("order by"), filter + " SQL must include ordering");
            assertTrue(normalizedSql.contains(expectedPredicate(filter) + " order by"), filter);
            assertEquals(-1, sql.indexOf("chartableorder"), filter);
            assertEquals(-1, sql.indexOf("has_quoteorder"), filter);
            assertEquals(-1, sql.indexOf("semantic_statusorder"), filter);
            assertEquals(-1, sql.indexOf("'missing'order"), filter);
            assertEquals(-1, sql.indexOf("'stale_quote'order"), filter);
        }
    }

    private static String expectedPredicate(String filter) {
        return switch (filter) {
            case "chart_ready" -> "and display_eligible";
            case "quote_available" -> "and has_quote";
            case "quote_only" -> "and has_quote and not chartable";
            case "quote_stale" -> "and quote_status = 'stale_quote'";
            case "metadata_only" -> "and catalog_source = 'market_metadata' and not has_quote "
                + "and not chartable and feature_count = 0";
            case "semantic_tagged" -> "and semantic_status <> 'missing'";
            case "unclassified" -> "and semantic_status = 'missing'";
            default -> throw new IllegalArgumentException(filter);
        };
    }

    @Test
    void generatedSqlUsesOnlyRealJdbcPlaceholders() {
        MarketCapabilityReadRequest request = new MarketCapabilityReadRequest(
            "rate",
            "indexed",
            "chart_ready",
            50,
            100,
            "v1",
            false,
            false
        );
        List<Object> summaryBindings = new ArrayList<>();
        List<Object> pageBindings = new ArrayList<>();

        String summarySql = JdbcMarketCapabilityReader.summarySql(request, summaryBindings);
        String pageSql = JdbcMarketCapabilityReader.pageSql(request, pageBindings);

        assertEquals(summaryBindings.size(), placeholderCount(summarySql));
        assertEquals(pageBindings.size(), placeholderCount(pageSql));
        assertTrue(summarySql.contains("market_feature_stats"));
        assertTrue(pageSql.contains("coalesce(mfs.feature_count, 0)"));
        assertTrue(pageSql.contains("coalesce(mfs.bbo_chart_count, 0) > 0"));
        assertTrue(pageSql.contains("history_bars_24h_count desc"));
        assertTrue(pageSql.contains("trade_24h_count desc"));
        assertTrue(pageSql.contains("quote_24h_count desc"));
        assertTrue(pageSql.contains("and display_eligible"));
        assertTrue(pageSql.contains("mfs.last_chart_ts_ms"));
        assertEquals(-1, summarySql.toLowerCase(Locale.ROOT).indexOf("from feature_outputs"));
        assertEquals(-1, pageSql.toLowerCase(Locale.ROOT).indexOf("from feature_outputs"));
    }

    @Test
    void rankedCteDoesNotSelfReference() {
        List<Object> bindings = new ArrayList<>();
        String sql = JdbcMarketCapabilityReader.pageSql(MarketCapabilityReadRequest.defaultRequest(), bindings)
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");

        int rankedStart = sql.indexOf("ranked as");
        int rankedEnd = sql.indexOf(") select *, count(*) over() as filtered_count from ranked", rankedStart);
        assertTrue(rankedStart >= 0, "ranked CTE must be present");
        assertTrue(rankedEnd > rankedStart, "page query must read from ranked CTE");
        String rankedCte = sql.substring(rankedStart, rankedEnd);
        assertTrue(rankedCte.contains("from enriched"), rankedCte);
        assertEquals(-1, rankedCte.indexOf("from ranked"), rankedCte);
    }

    @Test
    void wrapsSqlFailureWithCapabilityContext() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(List.of(), List.of()));
        jdbc.failExecuteQuery = true;
        JdbcMarketCapabilityReader reader = new JdbcMarketCapabilityReader(jdbc::openConnection);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> reader.readPage(MarketCapabilityReadRequest.defaultRequest())
        );

        assertTrue(thrown.getMessage().contains("market_metadata"));
        assertInstanceOf(SQLException.class, thrown.getCause());
    }

    private static void assertSqlFilter(String filter, String fragment) {
        List<Object> bindings = new ArrayList<>();
        String sql = JdbcMarketCapabilityReader.pageSql(new MarketCapabilityReadRequest(
            null,
            null,
            filter,
            10,
            0,
            "v1",
            false,
            false
        ), bindings).toLowerCase(Locale.ROOT);

        assertTrue(sql.contains(fragment), filter + " SQL missing " + fragment);
    }

    private static int placeholderCount(String sql) {
        int count = 0;
        for (int index = 0; index < sql.length(); index++) {
            if (sql.charAt(index) == '?') {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            row.put((String) keyValues[index], keyValues[index + 1]);
        }
        return row;
    }

    private static final class RecordingJdbc {
        private final List<List<Map<String, Object>>> resultsByStatement;
        private final List<String> preparedSqls = new ArrayList<>();
        private final List<List<Object>> bindingsByStatement = new ArrayList<>();
        private boolean failExecuteQuery;
        private int statementIndex = -1;

        private RecordingJdbc(List<List<Map<String, Object>>> resultsByStatement) {
            this.resultsByStatement = resultsByStatement;
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
                    statementIndex++;
                    preparedSqls.add((String) args[0]);
                    bindingsByStatement.add(new ArrayList<>());
                    yield preparedStatement(statementIndex);
                }
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement(int index) {
            InvocationHandler handler = (proxy, method, args) ->
                handlePreparedStatementInvocation(index, method, args);
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                handler
            );
        }

        private Object handlePreparedStatementInvocation(int index, Method method, Object[] args)
            throws SQLException {
            return switch (method.getName()) {
                case "setObject" -> {
                    int parameterIndex = (Integer) args[0];
                    List<Object> bindings = bindingsByStatement.get(index);
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
                    yield resultSet(resultsByStatement.get(index));
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet(List<Map<String, Object>> rows) {
            InvocationHandler handler = new InvocationHandler() {
                private int rowIndex = -1;

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "next" -> {
                            rowIndex++;
                            yield rowIndex < rows.size();
                        }
                        case "getString" -> rows.get(rowIndex).get((String) args[0]);
                        case "getObject" -> rows.get(rowIndex).get((String) args[0]);
                        case "getBoolean" -> Boolean.TRUE.equals(rows.get(rowIndex).get((String) args[0]));
                        case "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    };
                }
            };
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                handler
            );
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
