package edu.illinois.group8.replay.raw;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

public class TimescaleRawReplaySource implements RawReplaySource {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final RawIngressReplayConfig config;

    public TimescaleRawReplaySource(RawIngressReplayConfig config) {
        this.config = config;
    }

    @Override
    public List<RawReplayEvent> read(RawReplaySelection selection) {
        validateIdentifier("RAW_REPLAY_TABLE", config.rawTable());
        validateIdentifier("RAW_REPLAY_RAW_PAYLOAD_COLUMN", config.rawPayloadColumn());
        validateIdentifier("RAW_REPLAY_RECEIVE_TS_NS_COLUMN", config.receiveTsNsColumn());
        validateIdentifier("RAW_REPLAY_CONNECTION_ID_COLUMN", config.connectionIdColumn());
        validateIdentifier("RAW_REPLAY_SEQUENCE_COLUMN", config.sequenceColumn());
        validateIdentifier("RAW_REPLAY_RAW_EVENT_ID_COLUMN", config.rawEventIdColumn());
        validateIdentifier("RAW_REPLAY_MARKET_TICKER_COLUMN", config.marketTickerColumn());

        List<Object> bindings = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
            .append("select ")
            .append(config.rawPayloadColumn()).append(" as raw_payload, ")
            .append(config.receiveTsNsColumn()).append(" as receive_ts_ns, ")
            .append(config.connectionIdColumn()).append(" as connection_id, ")
            .append(config.sequenceColumn()).append(" as sequence, ")
            .append(config.rawEventIdColumn()).append(" as raw_event_id, ")
            .append(config.marketTickerColumn()).append(" as market_ticker ")
            .append("from ").append(config.rawTable())
            .append(" where 1=1");

        if (selection.startReceiveTsNs() != null) {
            sql.append(" and ").append(config.receiveTsNsColumn()).append(" >= ?");
            bindings.add(selection.startReceiveTsNs());
        }
        if (selection.endReceiveTsNs() != null) {
            sql.append(" and ").append(config.receiveTsNsColumn()).append(" <= ?");
            bindings.add(selection.endReceiveTsNs());
        }
        appendInClause(sql, bindings, config.marketTickerColumn(), selection.marketTickers());
        appendInClause(sql, bindings, config.rawEventIdColumn(), selection.rawEventIds());
        sql.append(" order by ").append(config.receiveTsNsColumn()).append(" asc, ")
            .append(config.sequenceColumn()).append(" asc");
        if (selection.maxEvents() > 0L) {
            sql.append(" limit ?");
            bindings.add(selection.maxEvents());
        }

        try (
            Connection connection = openConnection();
            PreparedStatement statement = connection.prepareStatement(sql.toString())
        ) {
            for (int index = 0; index < bindings.size(); index++) {
                statement.setObject(index + 1, bindings.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<RawReplayEvent> events = new ArrayList<>();
                while (resultSet.next()) {
                    events.add(readEvent(resultSet));
                }
                return List.copyOf(events);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read raw replay events from " + config.rawTable(), e);
        }
    }

    @Override
    public String description() {
        return "timescale:" + config.rawTable();
    }

    private Connection openConnection() throws SQLException {
        if (config.databaseUser().isBlank()) {
            return DriverManager.getConnection(config.databaseUrl());
        }
        Properties properties = new Properties();
        properties.setProperty("user", config.databaseUser());
        if (!config.databasePassword().isBlank()) {
            properties.setProperty("password", config.databasePassword());
        }
        return DriverManager.getConnection(config.databaseUrl(), properties);
    }

    private RawReplayEvent readEvent(ResultSet resultSet) throws SQLException {
        String rawEventId = string(resultSet, "raw_event_id");
        Long receiveTsNs = longOrNull(resultSet, "receive_ts_ns");
        long sequence = longOrDefault(resultSet, "sequence", 0L);
        String sourcePosition = rawEventId.isBlank()
            ? receiveTsNs + "/" + sequence
            : rawEventId;
        return new RawReplayEvent(
            resultSet.getString("raw_payload"),
            receiveTsNs,
            string(resultSet, "connection_id"),
            sequence,
            rawEventId,
            string(resultSet, "market_ticker"),
            description(),
            sourcePosition
        );
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

    private static void validateIdentifier(String envName, String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(envName + " must be a SQL identifier, got: " + identifier);
        }
    }

    private static String string(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? "" : value;
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

    private static long longOrDefault(ResultSet resultSet, String column, long defaultValue) throws SQLException {
        Long value = longOrNull(resultSet, column);
        return value == null ? defaultValue : value;
    }
}
