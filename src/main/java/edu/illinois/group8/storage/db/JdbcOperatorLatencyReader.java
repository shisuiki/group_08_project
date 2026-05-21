package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

public final class JdbcOperatorLatencyReader {
    static final int QUERY_TIMEOUT_SECONDS = 2;

    static final String LATENCY_SQL = """
        with canonical as (
            select
                event_id,
                coalesce(market_ticker, '') as market_ticker,
                canonical_commit_seq,
                (extract(epoch from created_at) * 1000)::bigint as canonical_created_at_ms
            from canonical_events
            where event_id = ?
        ),
        timings as (
            select
                c.event_id,
                c.market_ticker,
                c.canonical_commit_seq,
                c.canonical_created_at_ms,
                fo.feature_output_created_at_ms,
                lms.latest_market_state_updated_at_ms,
                lms.last_canonical_commit_seq as latest_market_state_commit_seq
            from canonical c
            left join lateral (
                select
                    (extract(epoch from fo.created_at) * 1000)::bigint as feature_output_created_at_ms
                from feature_outputs fo
                where fo.source_event_id = c.event_id
                order by fo.created_at desc, fo.feature_event_id desc
                limit 1
            ) fo on true
            left join lateral (
                select
                    (extract(epoch from lms.updated_at) * 1000)::bigint as latest_market_state_updated_at_ms,
                    lms.last_canonical_commit_seq
                from latest_market_state lms
                where lms.last_canonical_commit_seq = c.canonical_commit_seq
                  and (c.market_ticker = '' or lms.market_ticker = c.market_ticker)
                order by lms.updated_at desc, lms.market_ticker asc
                limit 1
            ) lms on true
        )
        select
            event_id,
            market_ticker,
            canonical_commit_seq,
            coalesce(latest_market_state_commit_seq, -1) as latest_market_state_commit_seq,
            case
                when feature_output_created_at_ms is null then -1
                else greatest(0, feature_output_created_at_ms - canonical_created_at_ms)
            end as canonical_to_feature_ms,
            case
                when feature_output_created_at_ms is null or latest_market_state_updated_at_ms is null then -1
                else greatest(0, latest_market_state_updated_at_ms - feature_output_created_at_ms)
            end as feature_to_latest_state_ms,
            case
                when latest_market_state_updated_at_ms is null then -1
                else greatest(0, latest_market_state_updated_at_ms - canonical_created_at_ms)
            end as canonical_to_latest_state_ms
        from timings
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcOperatorLatencyReader(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcOperatorLatencyReader fromDriverManager(String url, String user, String password) {
        return new JdbcOperatorLatencyReader(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    public OperatorLatencyStatus read(String sourceEventId) {
        String normalizedSourceEventId = normalize(sourceEventId);
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(LATENCY_SQL)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, normalizedSourceEventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return OperatorLatencyStatus.missing(normalizedSourceEventId, "missing_canonical_event");
                }
                Long latestMarketStateCommitSeq = positiveOrNull(resultSet, "latest_market_state_commit_seq");
                Long canonicalToFeatureMs = positiveOrNull(resultSet, "canonical_to_feature_ms");
                Long featureToLatestStateMs = positiveOrNull(resultSet, "feature_to_latest_state_ms");
                Long canonicalToLatestStateMs = positiveOrNull(resultSet, "canonical_to_latest_state_ms");
                String reason = reason(canonicalToFeatureMs, canonicalToLatestStateMs);
                return new OperatorLatencyStatus(
                    reason == null ? "ok" : "degraded",
                    resultSet.getString("event_id"),
                    resultSet.getString("market_ticker"),
                    longOrNull(resultSet, "canonical_commit_seq"),
                    latestMarketStateCommitSeq,
                    canonicalToFeatureMs,
                    featureToLatestStateMs,
                    canonicalToLatestStateMs,
                    reason,
                    null
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read operator latency", e);
        }
    }

    private static String reason(Long canonicalToFeatureMs, Long canonicalToLatestStateMs) {
        if (canonicalToFeatureMs == null) {
            return "missing_feature_output";
        }
        if (canonicalToLatestStateMs == null) {
            return "missing_latest_market_state";
        }
        return null;
    }

    private static Long positiveOrNull(ResultSet resultSet, String column) throws SQLException {
        Long value = longOrNull(resultSet, column);
        return value == null || value < 0L ? null : value;
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

    private static String normalize(String sourceEventId) {
        if (sourceEventId == null || sourceEventId.isBlank()) {
            throw new IllegalArgumentException("sourceEventId must be non-blank");
        }
        return sourceEventId.trim();
    }
}
