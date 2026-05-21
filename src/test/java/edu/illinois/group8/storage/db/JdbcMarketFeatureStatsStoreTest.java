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
import java.sql.Types;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMarketFeatureStatsStoreTest {
    @Test
    void migrationCreatesMarketFeatureStatsReadModel() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("create table if not exists market_feature_stats"));
        assertTrue(sql.contains("market_ticker text primary key"));
        assertTrue(sql.contains("feature_count bigint not null default 0"));
        assertTrue(sql.contains("bbo_sample_count bigint not null default 0"));
        assertTrue(sql.contains("ticker_sample_count bigint not null default 0"));
        assertTrue(sql.contains("trade_sample_count bigint not null default 0"));
        assertTrue(sql.contains("first_chart_ts_ms bigint"));
        assertTrue(sql.contains("last_chart_ts_ms bigint"));
        assertTrue(sql.contains("latest_feature_event_ts_ms bigint"));
        assertTrue(sql.contains("market_feature_stats_counts_non_negative"));
        assertTrue(sql.contains("market_feature_stats_last_chart_ts_idx"));
        assertTrue(sql.contains("market_feature_stats_latest_feature_ts_idx"));
    }

    @Test
    void refreshBatchUsesBoundedFeatureOutputsAggregateAndTimestampFenceFields() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc(List.of("M1", "M2"));

        List<String> refreshed = JdbcMarketFeatureStatsStore.refreshFromFeatureOutputs(
            jdbc.openConnection(),
            5,
            "M0"
        );

        assertEquals(List.of("M1", "M2"), refreshed);
        assertEquals(1, jdbc.preparedSqls.size());
        String sql = jdbc.preparedSqls.get(0).toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("with candidate_markets as"));
        assertTrue(sql.contains("from feature_outputs"));
        assertTrue(sql.contains("order by market_ticker"));
        assertTrue(sql.contains("limit ?"));
        assertTrue(sql.contains("insert into market_feature_stats"));
        assertTrue(sql.contains("on conflict (market_ticker) do update"));
        assertTrue(sql.contains("jsonb_exists(fo.\"values\", 'midpoint_micros')"));
        assertTrue(sql.contains("jsonb_exists(fo.\"values\", 'price_micros')"));
        assertTrue(sql.contains("jsonb_exists(fo.\"values\", 'yes_bid_micros')"));
        assertTrue(sql.contains("jsonb_exists(fo.\"values\", 'yes_ask_micros')"));
        assertTrue(sql.contains("jsonb_exists(fo.\"values\", 'yes_price_micros')"));
        assertTrue(sql.contains("jsonb_exists(fo.\"values\", 'no_price_micros')"));
        assertEquals("M0", jdbc.parameters.get(1));
        assertEquals("M0", jdbc.parameters.get(2));
        assertEquals(5, jdbc.parameters.get(3));
    }

    @Test
    void refreshBatchBindsNullAfterCursorAndCapsLargeLimit() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());

        JdbcMarketFeatureStatsStore.refreshFromFeatureOutputs(
            jdbc.openConnection(),
            JdbcMarketFeatureStatsStore.MAX_REFRESH_LIMIT + 1,
            " "
        );

        assertEquals(new SqlNull(Types.VARCHAR), jdbc.parameters.get(1));
        assertEquals(new SqlNull(Types.VARCHAR), jdbc.parameters.get(2));
        assertEquals(JdbcMarketFeatureStatsStore.MAX_REFRESH_LIMIT, jdbc.parameters.get(3));
    }

    @Test
    void refreshMarketsDeduplicatesAndRefreshesAffectedMarketsInOneStatement() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc(List.of("M1"));

        List<String> refreshed = JdbcMarketFeatureStatsStore.refreshMarkets(
            jdbc.openConnection(),
            List.of(" M1 ", "M1", "", "M2")
        );

        assertEquals(List.of("M1"), refreshed);
        assertEquals(1, jdbc.preparedSqls.size());
        String sql = jdbc.preparedSqls.get(0).toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("with requested(market_ticker) as"));
        assertTrue(sql.contains("values (cast(? as text)), (cast(? as text))"));
        assertTrue(sql.contains("join requested"));
        assertTrue(sql.contains("from feature_outputs fo"));
        assertTrue(sql.contains("group by fo.market_ticker"));
        assertTrue(sql.contains("on conflict (market_ticker) do update"));
        assertEquals("M1", jdbc.parameters.get(1));
        assertEquals("M2", jdbc.parameters.get(2));
    }

    @Test
    void refreshMarketsSkipsEmptyTickerSetWithoutPreparingSql() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());

        List<String> refreshed = JdbcMarketFeatureStatsStore.refreshMarkets(
            jdbc.openConnection(),
            Arrays.asList(null, " ", "")
        );

        assertEquals(List.of(), refreshed);
        assertEquals(List.of(), jdbc.preparedSqls);
    }

    @Test
    void rejectsInvalidRefreshInputs() {
        assertThrows(IllegalArgumentException.class, () -> JdbcMarketFeatureStatsStore.refreshMarketsSql(0));
        assertThrows(
            IllegalArgumentException.class,
            () -> JdbcMarketFeatureStatsStore.refreshFromFeatureOutputs(
                new RecordingJdbc(List.of()).openConnection(),
                0,
                null
            )
        );
    }

    private static String migrationSql() throws Exception {
        try (InputStream inputStream = JdbcMarketFeatureStatsStoreTest.class.getClassLoader()
            .getResourceAsStream("db/migration/V015__market_feature_stats.sql")) {
            if (inputStream == null) {
                throw new IllegalStateException("missing V015 migration");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record SqlNull(int sqlType) {
    }

    private static final class RecordingJdbc {
        private final List<String> returnedTickers;
        private final List<String> preparedSqls = new ArrayList<>();
        private final Map<Integer, Object> parameters = new HashMap<>();

        private RecordingJdbc(List<String> returnedTickers) {
            this.returnedTickers = returnedTickers;
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
                    preparedSqls.add((String) args[0]);
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
                case "setString", "setInt" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setNull" -> {
                    parameters.put((Integer) args[0], new SqlNull((Integer) args[1]));
                    yield null;
                }
                case "executeQuery" -> resultSet();
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet() {
            InvocationHandler handler = new InvocationHandler() {
                private int index = -1;

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "next" -> {
                            index++;
                            yield index < returnedTickers.size();
                        }
                        case "getString" -> returnedTickers.get(index);
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
            if (!type.isPrimitive()) {
                return null;
            }
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
