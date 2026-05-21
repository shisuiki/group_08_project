package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class JdbcMarketFeatureStatsStore {
    static final int DEFAULT_REFRESH_LIMIT = 1_000;
    static final int MAX_REFRESH_LIMIT = 10_000;
    private static final String TABLE_NAME = "market_feature_stats";
    private static final String STATS_SELECT_COLUMNS = """
                fo.market_ticker,
                count(*) as feature_count,
                count(*) filter (where feature_name = 'feature.bbo') as bbo_sample_count,
                count(*) filter (where feature_name = 'feature.ticker_snapshot') as ticker_sample_count,
                count(*) filter (where feature_name = 'feature.trade_tape') as trade_sample_count,
                count(*) filter (where %s) as bbo_chart_count,
                count(*) filter (where %s) as ticker_chart_count,
                count(*) filter (where %s) as trade_chart_count,
                min(event_ts_ms) filter (where %s) as bbo_first_chart_ts_ms,
                max(event_ts_ms) filter (where %s) as bbo_last_chart_ts_ms,
                min(event_ts_ms) filter (where %s) as ticker_first_chart_ts_ms,
                max(event_ts_ms) filter (where %s) as ticker_last_chart_ts_ms,
                min(event_ts_ms) filter (where %s) as trade_first_chart_ts_ms,
                max(event_ts_ms) filter (where %s) as trade_last_chart_ts_ms,
                min(event_ts_ms) filter (where %s or %s or %s) as first_chart_ts_ms,
                max(event_ts_ms) filter (where %s or %s or %s) as last_chart_ts_ms,
                max(event_ts_ms) as latest_feature_event_ts_ms
        """.formatted(
            bboChartPredicate("fo"),
            tickerChartPredicate("fo"),
            tradeChartPredicate("fo"),
            bboChartPredicate("fo"),
            bboChartPredicate("fo"),
            tickerChartPredicate("fo"),
            tickerChartPredicate("fo"),
            tradeChartPredicate("fo"),
            tradeChartPredicate("fo"),
            bboChartPredicate("fo"),
            tickerChartPredicate("fo"),
            tradeChartPredicate("fo"),
            bboChartPredicate("fo"),
            tickerChartPredicate("fo"),
            tradeChartPredicate("fo")
        );
    static final String REFRESH_BATCH_SQL = """
        with candidate_markets as (
            select distinct market_ticker
            from feature_outputs
            where market_ticker is not null
              and market_ticker <> ''
              and (cast(? as text) is null or market_ticker > cast(? as text))
            order by market_ticker
            limit ?
        ),
        stats as (
            select
        """ + STATS_SELECT_COLUMNS + """
            from feature_outputs fo
            join candidate_markets candidate
              on candidate.market_ticker = fo.market_ticker
            group by fo.market_ticker
        )
        """;
    private static final String UPSERT_FROM_STATS_SQL = """
        insert into market_feature_stats (
            market_ticker,
            feature_count,
            bbo_sample_count,
            ticker_sample_count,
            trade_sample_count,
            bbo_chart_count,
            ticker_chart_count,
            trade_chart_count,
            bbo_first_chart_ts_ms,
            bbo_last_chart_ts_ms,
            ticker_first_chart_ts_ms,
            ticker_last_chart_ts_ms,
            trade_first_chart_ts_ms,
            trade_last_chart_ts_ms,
            first_chart_ts_ms,
            last_chart_ts_ms,
            latest_feature_event_ts_ms,
            updated_at
        )
        select
            market_ticker,
            feature_count,
            bbo_sample_count,
            ticker_sample_count,
            trade_sample_count,
            bbo_chart_count,
            ticker_chart_count,
            trade_chart_count,
            bbo_first_chart_ts_ms,
            bbo_last_chart_ts_ms,
            ticker_first_chart_ts_ms,
            ticker_last_chart_ts_ms,
            trade_first_chart_ts_ms,
            trade_last_chart_ts_ms,
            first_chart_ts_ms,
            last_chart_ts_ms,
            latest_feature_event_ts_ms,
            now()
        from stats
        on conflict (market_ticker) do update set
            feature_count = excluded.feature_count,
            bbo_sample_count = excluded.bbo_sample_count,
            ticker_sample_count = excluded.ticker_sample_count,
            trade_sample_count = excluded.trade_sample_count,
            bbo_chart_count = excluded.bbo_chart_count,
            ticker_chart_count = excluded.ticker_chart_count,
            trade_chart_count = excluded.trade_chart_count,
            bbo_first_chart_ts_ms = excluded.bbo_first_chart_ts_ms,
            bbo_last_chart_ts_ms = excluded.bbo_last_chart_ts_ms,
            ticker_first_chart_ts_ms = excluded.ticker_first_chart_ts_ms,
            ticker_last_chart_ts_ms = excluded.ticker_last_chart_ts_ms,
            trade_first_chart_ts_ms = excluded.trade_first_chart_ts_ms,
            trade_last_chart_ts_ms = excluded.trade_last_chart_ts_ms,
            first_chart_ts_ms = excluded.first_chart_ts_ms,
            last_chart_ts_ms = excluded.last_chart_ts_ms,
            latest_feature_event_ts_ms = excluded.latest_feature_event_ts_ms,
            updated_at = now()
        returning market_ticker
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcMarketFeatureStatsStore(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcMarketFeatureStatsStore fromDriverManager(String url, String user, String password) {
        return new JdbcMarketFeatureStatsStore(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    public List<String> refreshFromFeatureOutputs(int limit, String afterMarketTicker) {
        int normalizedLimit = normalizeRefreshLimit(limit);
        String normalizedAfter = normalizeTicker(afterMarketTicker);
        try (Connection connection = connectionFactory.openConnection()) {
            return refreshFromFeatureOutputs(connection, normalizedLimit, normalizedAfter);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to refresh " + TABLE_NAME + " from feature_outputs", e);
        }
    }

    static List<String> refreshFromFeatureOutputs(Connection connection, int limit, String afterMarketTicker)
        throws SQLException {
        int normalizedLimit = normalizeRefreshLimit(limit);
        String normalizedAfter = normalizeTicker(afterMarketTicker);
        try (PreparedStatement statement = connection.prepareStatement(REFRESH_BATCH_SQL + UPSERT_FROM_STATS_SQL)) {
            if (normalizedAfter == null) {
                statement.setNull(1, Types.VARCHAR);
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(1, normalizedAfter);
                statement.setString(2, normalizedAfter);
            }
            statement.setInt(3, normalizedLimit);
            return readReturnedTickers(statement);
        }
    }

    static List<String> refreshMarkets(Connection connection, Collection<String> marketTickers) throws SQLException {
        List<String> tickers = normalizeTickers(marketTickers);
        if (tickers.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement statement = connection.prepareStatement(refreshMarketsSql(tickers.size()))) {
            for (int index = 0; index < tickers.size(); index++) {
                statement.setString(index + 1, tickers.get(index));
            }
            return readReturnedTickers(statement);
        }
    }

    static String refreshMarketsSql(int marketCount) {
        if (marketCount < 1) {
            throw new IllegalArgumentException("marketCount must be positive");
        }
        StringBuilder values = new StringBuilder();
        for (int index = 0; index < marketCount; index++) {
            if (index > 0) {
                values.append(", ");
            }
            values.append("(cast(? as text))");
        }
        return """
            with requested(market_ticker) as (
                values %s
            ),
            stats as (
                select
            """.formatted(values) + STATS_SELECT_COLUMNS + """
                from feature_outputs fo
                join requested
                  on requested.market_ticker = fo.market_ticker
                group by fo.market_ticker
            )
            """ + UPSERT_FROM_STATS_SQL;
    }

    private static List<String> readReturnedTickers(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            List<String> tickers = new ArrayList<>();
            while (resultSet.next()) {
                tickers.add(resultSet.getString("market_ticker"));
            }
            return List.copyOf(tickers);
        }
    }

    static List<String> normalizeTickers(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> tickers = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalizeTicker(value);
            if (normalized != null) {
                tickers.add(normalized);
            }
        }
        return List.copyOf(tickers);
    }

    private static int normalizeRefreshLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return Math.min(limit, MAX_REFRESH_LIMIT);
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null) {
            return null;
        }
        String trimmed = ticker.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String bboChartPredicate(String alias) {
        return alias + ".feature_name = 'feature.bbo'"
            + " and " + alias + ".event_ts_ms is not null"
            + " and jsonb_exists(" + alias + ".\"values\", 'midpoint_micros')";
    }

    private static String tickerChartPredicate(String alias) {
        return alias + ".feature_name = 'feature.ticker_snapshot'"
            + " and " + alias + ".event_ts_ms is not null"
            + " and (jsonb_exists(" + alias + ".\"values\", 'price_micros')"
            + " or (jsonb_exists(" + alias + ".\"values\", 'yes_bid_micros')"
            + " and jsonb_exists(" + alias + ".\"values\", 'yes_ask_micros')))";
    }

    private static String tradeChartPredicate(String alias) {
        return alias + ".feature_name = 'feature.trade_tape'"
            + " and " + alias + ".event_ts_ms is not null"
            + " and (jsonb_exists(" + alias + ".\"values\", 'yes_price_micros')"
            + " or jsonb_exists(" + alias + ".\"values\", 'no_price_micros'))";
    }
}
