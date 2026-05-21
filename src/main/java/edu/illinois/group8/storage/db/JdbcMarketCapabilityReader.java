package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class JdbcMarketCapabilityReader implements MarketCapabilityReader {
    static final long LIVE_QUOTE_STALE_AFTER_MS = 15_000L;
    private static final String TABLE_NAME =
        "market_metadata + latest_market_state + feature_outputs + market_semantic_metadata";
    private static final String CTE_SQL = """
        with feature_symbols as (
            select distinct market_ticker
            from feature_outputs
            where market_ticker is not null
              and market_ticker <> ''
        ),
        feature_counts as (
            select
                market_ticker,
                count(*) as feature_count,
                count(*) filter (where feature_name = 'feature.bbo') as bbo_sample_count,
                count(*) filter (where feature_name = 'feature.trade_tape') as trade_sample_count,
                count(*) filter (where feature_name = 'feature.ticker_snapshot') as ticker_sample_count,
                count(*) filter (
                    where feature_name = 'feature.bbo'
                      and event_ts_ms is not null
                      and "values" ? 'midpoint_micros'
                ) as bbo_chart_count,
                count(*) filter (
                    where feature_name = 'feature.ticker_snapshot'
                      and event_ts_ms is not null
                      and (
                          "values" ? 'price_micros'
                          or ("values" ? 'yes_bid_micros' and "values" ? 'yes_ask_micros')
                      )
                ) as ticker_chart_count,
                count(*) filter (
                    where feature_name = 'feature.trade_tape'
                      and event_ts_ms is not null
                      and ("values" ? 'yes_price_micros' or "values" ? 'no_price_micros')
                ) as trade_chart_count,
                count(*) filter (
                    where feature_name = 'feature.bbo'
                      and event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 3600000)
                      and "values" ? 'midpoint_micros'
                ) + count(*) filter (
                    where feature_name = 'feature.ticker_snapshot'
                      and event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 3600000)
                      and (
                          "values" ? 'price_micros'
                          or ("values" ? 'yes_bid_micros' and "values" ? 'yes_ask_micros')
                      )
                ) + count(*) filter (
                    where feature_name = 'feature.trade_tape'
                      and event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 3600000)
                      and ("values" ? 'yes_price_micros' or "values" ? 'no_price_micros')
                ) as bbo_1h_count,
                count(*) filter (
                    where feature_name = 'feature.bbo'
                      and event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
                      and "values" ? 'midpoint_micros'
                ) + count(*) filter (
                    where feature_name = 'feature.ticker_snapshot'
                      and event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
                      and (
                          "values" ? 'price_micros'
                          or ("values" ? 'yes_bid_micros' and "values" ? 'yes_ask_micros')
                      )
                ) + count(*) filter (
                    where feature_name = 'feature.trade_tape'
                      and event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
                      and ("values" ? 'yes_price_micros' or "values" ? 'no_price_micros')
                ) as bbo_24h_count
            from feature_outputs
            where market_ticker is not null
              and market_ticker <> ''
            group by market_ticker
        ),
        universe as (
            select
                coalesce(mm.market_ticker, fs.market_ticker) as market_ticker,
                mm.event_ticker,
                mm.series_ticker,
                coalesce(mm.status, 'indexed') as status,
                case when mm.market_ticker is null then 'feature_outputs' else 'market_metadata' end as catalog_source
            from feature_symbols fs
            full join market_metadata mm
                on mm.market_ticker = fs.market_ticker
        ),
        enriched as (
            select
                u.market_ticker,
                u.event_ticker,
                u.series_ticker,
                u.status,
                u.catalog_source,
                (lms.market_ticker is not null) as has_latest_state,
                (
                    lms.last_event_ts_ms is not null
                    and (
                        lms.midpoint_micros is not null
                        or lms.best_bid_micros is not null
                        or lms.best_ask_micros is not null
                    )
                ) as has_quote,
                lms.last_event_ts_ms as quote_event_ts_ms,
                case when lms.last_event_ts_ms is null
                    then null
                    else greatest(0, ((extract(epoch from now()) * 1000)::bigint - lms.last_event_ts_ms))
                end as quote_age_ms,
                case
                    when lms.last_event_ts_ms is null
                      or (
                          lms.midpoint_micros is null
                          and lms.best_bid_micros is null
                          and lms.best_ask_micros is null
                      ) then 'missing_quote'
                    when greatest(0, ((extract(epoch from now()) * 1000)::bigint - lms.last_event_ts_ms)) <= ?
                        then 'live_quote'
                    else 'stale_quote'
                end as quote_status,
                coalesce(fc.feature_count, 0) as feature_count,
                coalesce(fc.bbo_sample_count, 0) as bbo_sample_count,
                coalesce(fc.trade_sample_count, 0) as trade_sample_count,
                coalesce(fc.ticker_sample_count, 0) as ticker_sample_count,
                (coalesce(fc.bbo_chart_count, 0) > 0) as has_bbo_history,
                (coalesce(fc.bbo_chart_count, 0) > 0) as chartable_from_bbo,
                (coalesce(fc.ticker_chart_count, 0) > 0) as chartable_from_ticker_snapshot,
                (coalesce(fc.trade_chart_count, 0) > 0) as chartable_from_trade_tape,
                case
                    when coalesce(fc.bbo_chart_count, 0) > 0 then 'bbo'
                    when coalesce(fc.ticker_chart_count, 0) > 0 then 'ticker_snapshot'
                    when coalesce(fc.trade_chart_count, 0) > 0 then 'trade_tape'
                    else null
                end as best_chart_source,
                (coalesce(fc.bbo_1h_count, 0) > 0) as chartable_1h,
                (coalesce(fc.bbo_24h_count, 0) > 0) as chartable_24h,
                (
                    coalesce(fc.bbo_chart_count, 0) > 0
                    or coalesce(fc.ticker_chart_count, 0) > 0
                    or coalesce(fc.trade_chart_count, 0) > 0
                ) as chartable,
                case
                    when coalesce(fc.bbo_1h_count, 0) > 0 then 'chartable_1h'
                    when coalesce(fc.bbo_24h_count, 0) > 0 then 'chartable_24h'
                    when coalesce(fc.bbo_chart_count, 0) > 0
                      or coalesce(fc.ticker_chart_count, 0) > 0
                      or coalesce(fc.trade_chart_count, 0) > 0 then 'chartable_history'
                    when lms.last_event_ts_ms is not null and (
                        lms.midpoint_micros is not null
                        or lms.best_bid_micros is not null
                        or lms.best_ask_micros is not null
                    ) then 'quote_only'
                    else 'not_chartable'
                end as chart_status,
                case
                    when coalesce(fc.bbo_chart_count, 0) > 0 then 'bbo_history_available'
                    when coalesce(fc.ticker_chart_count, 0) > 0 then 'ticker_snapshot_history_available'
                    when coalesce(fc.trade_chart_count, 0) > 0 then 'trade_tape_history_available'
                    when lms.last_event_ts_ms is not null and (
                        lms.midpoint_micros is not null
                        or lms.best_bid_micros is not null
                        or lms.best_ask_micros is not null
                    ) then 'quote_without_chart_history'
                    when u.catalog_source = 'market_metadata' then 'catalog_only'
                    else 'missing_quote_and_history'
                end as chart_reason,
                coalesce(smm.status, 'missing') as semantic_status,
                smm.sector as semantic_sector,
                smm.subsector as semantic_subsector,
                smm.event_type as semantic_event_type
            from universe u
            left join latest_market_state lms
                on lms.market_ticker = u.market_ticker
            left join feature_counts fc
                on fc.market_ticker = u.market_ticker
            left join market_semantic_metadata smm
                on smm.market_ticker = u.market_ticker
               and smm.taxonomy_version = ?
        )
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcMarketCapabilityReader(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcMarketCapabilityReader fromDriverManager(String url, String user, String password) {
        return new JdbcMarketCapabilityReader(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public MarketCapabilityPage readPage(MarketCapabilityReadRequest request) {
        MarketCapabilityReadRequest normalized = request == null
            ? MarketCapabilityReadRequest.defaultRequest()
            : request;
        try (Connection connection = connectionFactory.openConnection()) {
            MarketCapabilitySummary summary = readSummary(connection, normalized);
            PageRows rows = readRows(connection, normalized);
            long totalCount = rows.filteredCount >= 0L ? rows.filteredCount : summary.totalAssets();
            return new MarketCapabilityPage(
                summary,
                rows.rows,
                totalCount,
                normalized.limit(),
                normalized.offset()
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read market capabilities from " + TABLE_NAME, e);
        }
    }

    static String summarySql(MarketCapabilityReadRequest request, List<Object> bindings) {
        StringBuilder sql = new StringBuilder();
        appendCte(sql, request, bindings);
        sql.append("""
            select
                count(*) as total_assets,
                count(*) filter (where chartable) as chartable_count,
                count(*) filter (where has_quote) as quote_count,
                count(*) filter (where quote_status = 'stale_quote') as stale_quote_count,
                count(*) filter (where semantic_status = 'generated') as semantic_generated_count,
                count(*) filter (where semantic_status = 'review_required') as semantic_review_required_count,
                count(*) filter (where semantic_status = 'failed') as semantic_failed_count,
                count(*) filter (where semantic_status = 'rate_limited') as semantic_rate_limited_count,
                count(*) filter (where semantic_status = 'missing') as semantic_missing_count,
                count(*) filter (
                    where catalog_source = 'market_metadata'
                      and not has_quote
                      and not chartable
                      and feature_count = 0
                ) as metadata_only_count
            from enriched
            """);
        appendBaseWhere(sql, request, bindings);
        return sql.toString();
    }

    static String pageSql(MarketCapabilityReadRequest request, List<Object> bindings) {
        StringBuilder sql = new StringBuilder();
        appendCte(sql, request, bindings);
        sql.append("""
            select
                *,
                count(*) over() as filtered_count
            from enriched
            """);
        appendBaseWhere(sql, request, bindings);
        appendCapabilityFilter(sql, request);
        sql.append("""
            order by
                case when chartable_1h then 0
                     when chartable_24h then 1
                     when has_quote then 2
                     when has_bbo_history then 3
                     else 4
                end,
                quote_event_ts_ms desc nulls last,
                market_ticker asc
            offset ? limit ?
            """);
        bindings.add(request.offset());
        bindings.add(request.limit());
        return sql.toString();
    }

    private MarketCapabilitySummary readSummary(Connection connection, MarketCapabilityReadRequest request)
        throws SQLException {
        List<Object> bindings = new ArrayList<>();
        String sql = summarySql(request, bindings);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, bindings);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return MarketCapabilitySummary.empty();
                }
                return new MarketCapabilitySummary(
                    longValue(resultSet, "total_assets"),
                    longValue(resultSet, "chartable_count"),
                    longValue(resultSet, "quote_count"),
                    longValue(resultSet, "stale_quote_count"),
                    longValue(resultSet, "semantic_generated_count"),
                    longValue(resultSet, "semantic_review_required_count"),
                    longValue(resultSet, "semantic_failed_count"),
                    longValue(resultSet, "semantic_rate_limited_count"),
                    longValue(resultSet, "semantic_missing_count"),
                    longValue(resultSet, "metadata_only_count")
                );
            }
        }
    }

    private PageRows readRows(Connection connection, MarketCapabilityReadRequest request) throws SQLException {
        List<Object> bindings = new ArrayList<>();
        String sql = pageSql(request, bindings);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, bindings);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MarketCapability> rows = new ArrayList<>();
                long filteredCount = -1L;
                while (resultSet.next()) {
                    filteredCount = longValue(resultSet, "filtered_count");
                    rows.add(readCapability(resultSet));
                }
                return new PageRows(List.copyOf(rows), filteredCount);
            }
        }
    }

    private static void appendCte(StringBuilder sql, MarketCapabilityReadRequest request, List<Object> bindings) {
        sql.append(CTE_SQL);
        bindings.add(LIVE_QUOTE_STALE_AFTER_MS);
        bindings.add(request.taxonomyVersion());
    }

    private static void appendBaseWhere(
        StringBuilder sql,
        MarketCapabilityReadRequest request,
        List<Object> bindings
    ) {
        sql.append(" where 1 = 1");
        if (!request.includeSmoke()) {
            sql.append("""
                 and market_ticker not like 'LIVE-PRODUCT-SMOKE-%'
                 and coalesce(event_ticker, '') <> 'LIVE-PRODUCT-SMOKE'
                 and coalesce(series_ticker, '') <> 'LIVE-PRODUCT-SMOKE'
                """);
        }
        if (request.query() != null) {
            String pattern = "%" + request.query().toLowerCase(Locale.ROOT) + "%";
            sql.append("""
                 and (
                    lower(market_ticker) like ?
                    or lower(coalesce(event_ticker, '')) like ?
                    or lower(coalesce(series_ticker, '')) like ?
                    or lower(coalesce(status, '')) like ?
                    or lower(coalesce(semantic_sector, '')) like ?
                    or lower(coalesce(semantic_subsector, '')) like ?
                    or lower(coalesce(semantic_event_type, '')) like ?
                )
                """);
            for (int index = 0; index < 7; index++) {
                bindings.add(pattern);
            }
        }
        if (request.status() != null) {
            sql.append(" and status = ?");
            bindings.add(request.status());
        }
    }

    private static void appendCapabilityFilter(StringBuilder sql, MarketCapabilityReadRequest request) {
        switch (request.capabilityFilter()) {
            case "all" -> {
            }
            case "chart_ready" -> sql.append(" and chartable");
            case "quote_available" -> sql.append(" and has_quote");
            case "quote_only" -> sql.append(" and has_quote and not chartable");
            case "quote_stale" -> sql.append(" and quote_status = 'stale_quote'");
            case "metadata_only" -> sql.append("""
                 and catalog_source = 'market_metadata'
                 and not has_quote
                 and not chartable
                 and feature_count = 0
                """);
            case "semantic_tagged" -> sql.append(" and semantic_status <> 'missing'");
            case "unclassified" -> sql.append(" and semantic_status = 'missing'");
            default -> throw new IllegalArgumentException("Unsupported capability filter: " + request.capabilityFilter());
        }
    }

    private static MarketCapability readCapability(ResultSet resultSet) throws SQLException {
        return new MarketCapability(
            resultSet.getString("market_ticker"),
            resultSet.getString("event_ticker"),
            resultSet.getString("series_ticker"),
            resultSet.getString("status"),
            resultSet.getString("catalog_source"),
            resultSet.getBoolean("has_latest_state"),
            resultSet.getBoolean("has_quote"),
            longOrNull(resultSet, "quote_event_ts_ms"),
            longOrNull(resultSet, "quote_age_ms"),
            resultSet.getString("quote_status"),
            resultSet.getBoolean("has_bbo_history"),
            resultSet.getBoolean("chartable_from_bbo"),
            resultSet.getBoolean("chartable_from_ticker_snapshot"),
            resultSet.getBoolean("chartable_from_trade_tape"),
            resultSet.getString("best_chart_source"),
            resultSet.getBoolean("chartable_1h"),
            resultSet.getBoolean("chartable_24h"),
            resultSet.getBoolean("chartable"),
            resultSet.getString("chart_status"),
            resultSet.getString("chart_reason"),
            resultSet.getString("semantic_status"),
            resultSet.getString("semantic_sector"),
            resultSet.getString("semantic_subsector"),
            resultSet.getString("semantic_event_type"),
            longValue(resultSet, "feature_count"),
            longValue(resultSet, "bbo_sample_count"),
            longValue(resultSet, "trade_sample_count"),
            longValue(resultSet, "ticker_sample_count")
        );
    }

    private static void bind(PreparedStatement statement, List<Object> bindings) throws SQLException {
        for (int index = 0; index < bindings.size(); index++) {
            statement.setObject(index + 1, bindings.get(index));
        }
    }

    private static long longValue(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static Long longOrNull(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private record PageRows(List<MarketCapability> rows, long filteredCount) {
    }
}
