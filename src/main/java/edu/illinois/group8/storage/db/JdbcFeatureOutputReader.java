package edu.illinois.group8.storage.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.feature.FeatureOutput;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JdbcFeatureOutputReader implements FeatureOutputReader {
    private static final String TABLE_NAME = "feature_outputs";
    private static final TypeReference<Map<String, Object>> VALUE_MAP = new TypeReference<>() {
    };
    private static final String SELECT_COLUMNS = """
        select
            feature_event_id,
            source_event_id,
            feature_name,
            feature_version,
            market_ticker,
            event_ts_ms,
            created_at,
            "values"::text as values
        from feature_outputs
        """;

    private final JdbcConnectionFactory connectionFactory;
    private final ObjectMapper mapper;

    public JdbcFeatureOutputReader(JdbcConnectionFactory connectionFactory) {
        this(connectionFactory, new JsonCanonicalSerializer().mapper());
    }

    JdbcFeatureOutputReader(JdbcConnectionFactory connectionFactory, ObjectMapper mapper) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public static JdbcFeatureOutputReader fromDriverManager(String url, String user, String password) {
        return new JdbcFeatureOutputReader(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public List<FeatureOutput> read(FeatureOutputReadRequest request) {
        return readRows(request).stream()
            .map(FeatureOutputRow::output)
            .toList();
    }

    public List<FeatureOutputRow> readRows(FeatureOutputReadRequest request) {
        FeatureOutputReadRequest normalized = request == null
            ? FeatureOutputReadRequest.defaultRecent()
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
                List<FeatureOutputRow> outputs = new ArrayList<>();
                while (resultSet.next()) {
                    outputs.add(readRow(resultSet));
                }
                return List.copyOf(outputs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read feature outputs from " + TABLE_NAME, e);
        }
    }

    static String sql(FeatureOutputReadRequest request, List<Object> bindings) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append("where 1 = 1");
        appendInClause(sql, bindings, "feature_name", request.featureNames());
        if (request.marketTicker() != null) {
            sql.append(" and market_ticker = ?");
            bindings.add(request.marketTicker());
        }
        if (request.fromEventTsMs() != null) {
            sql.append(" and event_ts_ms >= ?");
            bindings.add(request.fromEventTsMs());
        }
        if (request.toEventTsMs() != null) {
            sql.append(" and event_ts_ms <= ?");
            bindings.add(request.toEventTsMs());
        }
        if (request.after() != null) {
            Timestamp createdAt = Timestamp.from(request.after().createdAt());
            sql.append(" and (created_at > ? or (created_at = ? and feature_event_id > ?))");
            bindings.add(createdAt);
            bindings.add(createdAt);
            bindings.add(request.after().featureEventId());
        }
        if (request.ascending()) {
            sql.append(" order by created_at asc, feature_event_id asc");
        } else {
            sql.append(" order by event_ts_ms desc nulls last, feature_event_id asc");
        }
        sql.append(" limit ?");
        bindings.add(request.maxRows());
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

    private FeatureOutputRow readRow(ResultSet resultSet) throws SQLException {
        String featureEventId = resultSet.getString("feature_event_id");
        Instant createdAt = instantOrNull(resultSet, "created_at");
        String featureName = resultSet.getString("feature_name");
        String valuesJson = resultSet.getString("values");
        try {
            Map<String, Object> values = mapper.readValue(valuesJson, VALUE_MAP);
            return new FeatureOutputRow(
                featureEventId,
                createdAt,
                new FeatureOutput(
                    featureName,
                    featureName,
                    resultSet.getString("market_ticker"),
                    longOrNull(resultSet, "event_ts_ms"),
                    resultSet.getString("source_event_id"),
                    values
                )
            );
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to map feature output row " + featureEventId,
                e
            );
        }
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

    private static Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        return Instant.parse(value.toString());
    }
}
