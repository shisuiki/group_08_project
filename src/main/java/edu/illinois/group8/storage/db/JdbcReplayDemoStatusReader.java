package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcReplayDemoStatusReader {
    public static final String DEFAULT_REPLAY_ID = "demo-db-primary-long-replay";

    static final String STATUS_SQL = """
        with replay_events as (
            select event_id, market_ticker, event_ts_ms, canonical_commit_seq
            from canonical_events
            where replay_id = ?
        ),
        replay_markets as (
            select distinct market_ticker
            from replay_events
            where market_ticker is not null and market_ticker <> ''
        )
        select
            (select count(*) from replay_markets) as market_count,
            (select count(*) from replay_events) as canonical_event_count,
            (
                select count(*)
                from feature_outputs feature
                where feature.source_event_id in (select event_id from replay_events)
            ) as feature_output_count,
            (
                select count(*)
                from latest_market_state latest
                where latest.market_ticker in (select market_ticker from replay_markets)
            ) as latest_market_state_count,
            (select min(event_ts_ms) from replay_events) as first_event_ts_ms,
            (select max(event_ts_ms) from replay_events) as last_event_ts_ms,
            (select min(canonical_commit_seq) from replay_events) as first_canonical_commit_seq,
            (select max(canonical_commit_seq) from replay_events) as last_canonical_commit_seq,
            (
                select coalesce(string_agg(market_ticker, ',' order by market_ticker), '')
                from replay_markets
            ) as available_symbols
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcReplayDemoStatusReader(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcReplayDemoStatusReader fromDriverManager(String url, String user, String password) {
        return new JdbcReplayDemoStatusReader(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    public ReplayDemoStatus readDefault() {
        return read(DEFAULT_REPLAY_ID);
    }

    public ReplayDemoStatus read(String replayId) {
        String normalizedReplayId = normalizeReplayId(replayId);
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(STATUS_SQL)) {
            statement.setString(1, normalizedReplayId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return ReplayDemoStatus.fromCounts(
                        normalizedReplayId,
                        0L,
                        0L,
                        0L,
                        0L,
                        null,
                        null,
                        null,
                        null,
                        List.of()
                    );
                }
                return ReplayDemoStatus.fromCounts(
                    normalizedReplayId,
                    longValue(resultSet, "market_count"),
                    longValue(resultSet, "canonical_event_count"),
                    longValue(resultSet, "feature_output_count"),
                    longValue(resultSet, "latest_market_state_count"),
                    longOrNull(resultSet, "first_event_ts_ms"),
                    longOrNull(resultSet, "last_event_ts_ms"),
                    longOrNull(resultSet, "first_canonical_commit_seq"),
                    longOrNull(resultSet, "last_canonical_commit_seq"),
                    parseSymbols(stringValue(resultSet, "available_symbols"))
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read replay demo status", e);
        }
    }

    private static String normalizeReplayId(String replayId) {
        String normalized = replayId == null ? "" : replayId.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("replayId must not be blank");
        }
        return normalized;
    }

    private static List<String> parseSymbols(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> symbols = new ArrayList<>();
        for (String symbol : csv.split(",")) {
            String trimmed = symbol.trim();
            if (!trimmed.isBlank()) {
                symbols.add(trimmed);
            }
        }
        return List.copyOf(symbols);
    }

    private static long longValue(ResultSet resultSet, String column) throws SQLException {
        Long value = longOrNull(resultSet, column);
        return value == null ? 0L : value;
    }

    private static Long longOrNull(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String stringValue(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        return value == null ? "" : String.valueOf(value);
    }
}
