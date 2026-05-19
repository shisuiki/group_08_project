package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

public final class JdbcLatestMarketStateStore implements LatestMarketStateStore {
    static final String UPSERT_SQL = """
        insert into latest_market_state (
            market_ticker,
            last_event_ts_ms,
            last_canonical_event_id,
            best_bid_micros,
            best_ask_micros,
            midpoint_micros,
            open_interest,
            payload
        ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        on conflict (market_ticker) do update set
            last_event_ts_ms = excluded.last_event_ts_ms,
            last_canonical_event_id = excluded.last_canonical_event_id,
            best_bid_micros = excluded.best_bid_micros,
            best_ask_micros = excluded.best_ask_micros,
            midpoint_micros = excluded.midpoint_micros,
            open_interest = excluded.open_interest,
            payload = excluded.payload,
            updated_at = now()
        where latest_market_state.last_event_ts_ms is null
           or (
               excluded.last_event_ts_ms is not null
               and excluded.last_event_ts_ms >= latest_market_state.last_event_ts_ms
           )
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcLatestMarketStateStore(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcLatestMarketStateStore fromDriverManager(String url, String user, String password) {
        return new JdbcLatestMarketStateStore(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public void upsertLatestMarketState(LatestMarketState state) throws Exception {
        Objects.requireNonNull(state, "state");
        validateMarketTicker(state.marketTicker());

        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            bindState(statement, state);
            statement.executeUpdate();
        }
    }

    private static void bindState(PreparedStatement statement, LatestMarketState state) throws SQLException {
        statement.setString(1, state.marketTicker());
        setNullableLong(statement, 2, state.lastEventTsMs());
        statement.setString(3, state.lastCanonicalEventId());
        setNullableLong(statement, 4, state.bestBidMicros());
        setNullableLong(statement, 5, state.bestAskMicros());
        setNullableLong(statement, 6, state.midpointMicros());
        setNullableLong(statement, 7, state.openInterest());
        setNullableJson(statement, 8, state.payload());
    }

    private static void validateMarketTicker(String marketTicker) {
        if (marketTicker == null || marketTicker.isBlank()) {
            throw new IllegalArgumentException("marketTicker must not be blank");
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
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
