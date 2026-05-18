package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcCanonicalEventReader implements CanonicalDbEventReader {
    private static final String TABLE_NAME = "canonical_events";
    private static final String SELECT_COLUMNS = """
        select
            canonical_commit_seq,
            event_id,
            raw_event_id,
            replay_id,
            stream_name,
            event_type,
            schema_version,
            market_ticker,
            event_ts_ms,
            ingest_ts_ns,
            publish_ts_ns,
            payload::text as payload
        from canonical_events
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcCanonicalEventReader(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public List<CanonicalDbReadEvent> read(CanonicalDbReadRequest request) {
        CanonicalDbReadRequest normalized = request == null ? CanonicalDbReadRequest.fromStart() : request;
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
                List<CanonicalDbReadEvent> events = new ArrayList<>();
                while (resultSet.next()) {
                    events.add(readEvent(resultSet));
                }
                return List.copyOf(events);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read canonical events from " + TABLE_NAME, e);
        }
    }

    private static String sql(CanonicalDbReadRequest request, List<Object> bindings) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
            .append("where canonical_commit_seq > ?");
        bindings.add(request.cursor().lastCommitSeq());
        appendInClause(sql, bindings, "stream_name", request.streams());
        appendInClause(sql, bindings, "market_ticker", request.marketTickers());
        if (!request.replayId().isEmpty()) {
            sql.append(" and replay_id = ?");
            bindings.add(request.replayId());
        } else if (!request.includeReplayEvents()) {
            sql.append(" and replay_id is null");
        }
        sql.append(" order by canonical_commit_seq asc");
        if (request.maxEvents() > 0) {
            sql.append(" limit ?");
            bindings.add(request.maxEvents());
        }
        return sql.toString();
    }

    private static void appendInClause(
        StringBuilder sql,
        List<Object> bindings,
        String column,
        List<String> values
    ) {
        if (values.isEmpty()) {
            return;
        }
        sql.append(" and ").append(column).append(" in (");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("?");
            bindings.add(values.get(index));
        }
        sql.append(")");
    }

    private static CanonicalDbReadEvent readEvent(ResultSet resultSet) throws SQLException {
        return new CanonicalDbReadEvent(
            resultSet.getLong("canonical_commit_seq"),
            resultSet.getString("event_id"),
            resultSet.getString("raw_event_id"),
            resultSet.getString("replay_id"),
            resultSet.getString("stream_name"),
            resultSet.getString("event_type"),
            resultSet.getInt("schema_version"),
            resultSet.getString("market_ticker"),
            longOrNull(resultSet, "event_ts_ms"),
            longOrNull(resultSet, "ingest_ts_ns"),
            longOrNull(resultSet, "publish_ts_ns"),
            resultSet.getString("payload")
        );
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
}
