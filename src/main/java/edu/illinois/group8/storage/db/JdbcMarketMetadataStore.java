package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

public final class JdbcMarketMetadataStore implements MarketMetadataStore {
    static final String UPSERT_SQL = """
        insert into market_metadata (
            market_ticker,
            event_ticker,
            series_ticker,
            status,
            open_time,
            close_time,
            settlement_time,
            rules_payload,
            market_payload
        ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
        on conflict (market_ticker) do update set
            event_ticker = excluded.event_ticker,
            series_ticker = excluded.series_ticker,
            status = excluded.status,
            open_time = excluded.open_time,
            close_time = excluded.close_time,
            settlement_time = excluded.settlement_time,
            rules_payload = excluded.rules_payload,
            market_payload = excluded.market_payload,
            updated_at = now()
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcMarketMetadataStore(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcMarketMetadataStore fromDriverManager(String url, String user, String password) {
        return new JdbcMarketMetadataStore(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public void upsertMarketMetadata(MarketMetadata metadata) throws Exception {
        Objects.requireNonNull(metadata, "metadata");

        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            bindMetadata(statement, metadata);
            statement.executeUpdate();
        }
    }

    private static void bindMetadata(PreparedStatement statement, MarketMetadata metadata) throws SQLException {
        statement.setString(1, metadata.marketTicker());
        statement.setString(2, metadata.eventTicker());
        statement.setString(3, metadata.seriesTicker());
        statement.setString(4, metadata.status());
        setNullableTimestamp(statement, 5, metadata.openTime());
        setNullableTimestamp(statement, 6, metadata.closeTime());
        setNullableTimestamp(statement, 7, metadata.settlementTime());
        setNullableJson(statement, 8, metadata.rulesPayload());
        statement.setString(9, metadata.marketPayload());
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
        }
    }

    private static void setNullableJson(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
