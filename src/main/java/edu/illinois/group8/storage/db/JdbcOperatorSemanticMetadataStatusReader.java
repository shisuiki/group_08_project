package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

public final class JdbcOperatorSemanticMetadataStatusReader {
    static final int QUERY_TIMEOUT_SECONDS = 2;

    static final String STATUS_SQL = """
        select
            count(*) filter (where status = 'generated') as generated_count,
            count(*) filter (where status = 'review_required') as review_required_count,
            count(*) filter (where status = 'failed') as failed_count,
            count(*) filter (where status = 'rate_limited') as rate_limited_count,
            max(generated_at) filter (where status = 'generated') as last_generated_at
        from market_semantic_metadata
        where taxonomy_version = ?
        """;

    private final JdbcConnectionFactory connectionFactory;
    private final String model;
    private final String fallbackModel;
    private final String taxonomyVersion;

    public JdbcOperatorSemanticMetadataStatusReader(
        JdbcConnectionFactory connectionFactory,
        String model,
        String fallbackModel,
        String taxonomyVersion
    ) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.model = value(model);
        this.fallbackModel = value(fallbackModel);
        this.taxonomyVersion = nonBlank(taxonomyVersion, "taxonomyVersion");
    }

    public static JdbcOperatorSemanticMetadataStatusReader fromDriverManager(
        String url,
        String user,
        String password,
        String model,
        String fallbackModel,
        String taxonomyVersion
    ) {
        return new JdbcOperatorSemanticMetadataStatusReader(
            JdbcConnectionFactories.fromDriverManager(url, user, password),
            model,
            fallbackModel,
            taxonomyVersion
        );
    }

    public OperatorSemanticMetadataStatus read() {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(STATUS_SQL)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, taxonomyVersion);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("semantic metadata status query returned no rows");
                }
                long generated = longValue(resultSet, "generated_count");
                long reviewRequired = longValue(resultSet, "review_required_count");
                long failed = longValue(resultSet, "failed_count");
                long rateLimited = longValue(resultSet, "rate_limited_count");
                Instant lastGeneratedAt = instantOrNull(resultSet, "last_generated_at");
                Long lastGeneratedAgeMs = lastGeneratedAt == null
                    ? null
                    : Math.max(0L, System.currentTimeMillis() - lastGeneratedAt.toEpochMilli());
                return new OperatorSemanticMetadataStatus(
                    generated > 0 ? "ok" : "stale",
                    true,
                    model,
                    fallbackModel,
                    taxonomyVersion,
                    generated,
                    reviewRequired,
                    failed,
                    rateLimited,
                    lastGeneratedAt,
                    lastGeneratedAgeMs,
                    null
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read market_semantic_metadata status", e);
        }
    }

    private static long longValue(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
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

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value.trim();
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
