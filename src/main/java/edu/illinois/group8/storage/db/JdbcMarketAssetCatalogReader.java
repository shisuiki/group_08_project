package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcMarketAssetCatalogReader implements MarketMetadataReader {
    private static final String TABLE_NAME = "market_metadata + feature_outputs";
    static final String SELECT_COLUMNS = """
        with feature_symbols as (
            select distinct market_ticker
            from feature_outputs
            where market_ticker is not null
              and market_ticker <> ''
        )
        select
            coalesce(mm.market_ticker, fs.market_ticker) as market_ticker,
            mm.event_ticker,
            mm.series_ticker,
            coalesce(mm.status, 'indexed') as status,
            mm.open_time,
            mm.close_time,
            mm.settlement_time,
            coalesce(mm.rules_payload::text, '{}') as rules_payload,
            coalesce(mm.market_payload::text, '{}') as market_payload
        from feature_symbols fs
        full join market_metadata mm
            on mm.market_ticker = fs.market_ticker
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcMarketAssetCatalogReader(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcMarketAssetCatalogReader fromDriverManager(String url, String user, String password) {
        return new JdbcMarketAssetCatalogReader(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public List<MarketMetadata> read(MarketMetadataReadRequest request) {
        MarketMetadataReadRequest normalized = request == null
            ? MarketMetadataReadRequest.defaultSearch()
            : request;
        List<Object> bindings = new ArrayList<>();
        String sql = sql(normalized, bindings);

        try (
            Connection connection = connectionFactory.openConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            for (int index = 0; index < bindings.size(); index++) {
                statement.setObject(index + 1, bindings.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MarketMetadata> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(readMetadata(resultSet));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read market asset catalog from " + TABLE_NAME, e);
        }
    }

    static String sql(MarketMetadataReadRequest request, List<Object> bindings) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append("where 1 = 1");
        if (request.marketTicker() != null) {
            sql.append(" and coalesce(mm.market_ticker, fs.market_ticker) = ?");
            bindings.add(request.marketTicker());
        }
        if (request.seriesTicker() != null) {
            sql.append(" and mm.series_ticker = ?");
            bindings.add(request.seriesTicker());
        }
        if (request.status() != null) {
            sql.append(" and coalesce(mm.status, 'indexed') = ?");
            bindings.add(request.status());
        }
        if (request.excludeGeneratedTaxonomyVersion() != null) {
            sql.append("""
                 and not exists (
                    select 1
                    from market_semantic_metadata smm
                    where smm.market_ticker = coalesce(mm.market_ticker, fs.market_ticker)
                      and smm.taxonomy_version = ?
                      and smm.status = 'generated'
                )
                """);
            bindings.add(request.excludeGeneratedTaxonomyVersion());
        }
        sql.append("""
             order by
                case when mm.market_ticker is null then 1 else 0 end,
                mm.series_ticker asc nulls last,
                coalesce(mm.market_ticker, fs.market_ticker) asc
            """);
        sql.append(" limit ?");
        bindings.add(request.maxRows());
        return sql.toString();
    }

    private static MarketMetadata readMetadata(ResultSet resultSet) throws SQLException {
        return new MarketMetadata(
            resultSet.getString("market_ticker"),
            resultSet.getString("event_ticker"),
            resultSet.getString("series_ticker"),
            resultSet.getString("status"),
            instantOrNull(resultSet, "open_time"),
            instantOrNull(resultSet, "close_time"),
            instantOrNull(resultSet, "settlement_time"),
            resultSet.getString("rules_payload"),
            resultSet.getString("market_payload")
        );
    }

    private static Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return Instant.parse(value.toString());
    }
}
