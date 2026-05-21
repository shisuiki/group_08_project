package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

public final class JdbcOperatorPipelineStatusReader {
    static final long RECENT_WINDOW_SECONDS = 900L;
    static final int QUERY_TIMEOUT_SECONDS = 2;
    static final String STATUS_SQL = """
        select
            coalesce((
                select last_commit_seq
                from featureplant_cursors
                where cursor_name = ?
            ), 0) as cursor_commit_seq,
            coalesce((
                select max(canonical_commit_seq)
                from canonical_events
                where replay_id is null
            ), 0) as canonical_max_commit_seq,
            (
                select last_canonical_commit_seq
                from latest_market_state
                order by last_canonical_commit_seq desc nulls last, updated_at desc, market_ticker asc
                limit 1
            ) as latest_market_state_commit_seq,
            (
                select case
                    when max(updated_at) is null then null
                    else greatest(0, (extract(epoch from (now() - max(updated_at))) * 1000)::bigint)
                end
                from latest_market_state
            ) as latest_state_age_ms,
            (
                select count(*)
                from canonical_events
                where replay_id is null
                  and created_at >= now() - (? * interval '1 second')
            ) as recent_canonical_events,
            (
                select count(*)
                from feature_outputs
                where created_at >= now() - (? * interval '1 second')
            ) as recent_feature_outputs,
            (
                select count(*)
                from latest_market_state
                where updated_at >= now() - (? * interval '1 second')
            ) as recent_latest_market_states
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcOperatorPipelineStatusReader(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcOperatorPipelineStatusReader fromDriverManager(String url, String user, String password) {
        return new JdbcOperatorPipelineStatusReader(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    public OperatorPipelineStatus read(String cursorName) {
        String normalizedCursorName = JdbcFeaturePlantCursorStore.normalizeCursorName(cursorName);
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(STATUS_SQL)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, normalizedCursorName);
            statement.setLong(2, RECENT_WINDOW_SECONDS);
            statement.setLong(3, RECENT_WINDOW_SECONDS);
            statement.setLong(4, RECENT_WINDOW_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("operator pipeline status query returned no rows");
                }
                long cursorCommitSeq = longValue(resultSet, "cursor_commit_seq");
                long canonicalMaxCommitSeq = longValue(resultSet, "canonical_max_commit_seq");
                Long latestMarketStateCommitSeq = longOrNull(resultSet, "latest_market_state_commit_seq");
                Long latestStateAgeMs = longOrNull(resultSet, "latest_state_age_ms");
                long recentCanonicalEvents = longValue(resultSet, "recent_canonical_events");
                long recentFeatureOutputs = longValue(resultSet, "recent_feature_outputs");
                long recentLatestMarketStates = longValue(resultSet, "recent_latest_market_states");
                long cursorLagEvents = Math.max(0L, canonicalMaxCommitSeq - cursorCommitSeq);
                return new OperatorPipelineStatus(
                    status(canonicalMaxCommitSeq, cursorLagEvents, latestMarketStateCommitSeq),
                    normalizedCursorName,
                    cursorCommitSeq,
                    canonicalMaxCommitSeq,
                    cursorLagEvents,
                    latestMarketStateCommitSeq,
                    latestStateAgeMs,
                    recentCanonicalEvents,
                    recentFeatureOutputs,
                    recentLatestMarketStates,
                    RECENT_WINDOW_SECONDS,
                    null
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read operator pipeline status", e);
        }
    }

    private static String status(long canonicalMaxCommitSeq, long cursorLagEvents, Long latestMarketStateCommitSeq) {
        if (canonicalMaxCommitSeq == 0L && latestMarketStateCommitSeq == null) {
            return "stale";
        }
        if (cursorLagEvents > 0L || latestMarketStateCommitSeq == null) {
            return "degraded";
        }
        return "ok";
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
}
