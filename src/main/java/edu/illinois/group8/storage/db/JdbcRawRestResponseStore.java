package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

public final class JdbcRawRestResponseStore implements RawRestResponseStore {
    static final String RAW_REST_INSERT_SQL = """
        insert into raw_rest_responses (
            raw_rest_response_id,
            endpoint,
            ticker,
            fetch_ts_ns,
            fetch_wall_ts,
            payload_sha256,
            raw_payload
        ) values (?, ?, ?, ?, ?, ?, ?)
        on conflict do nothing
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcRawRestResponseStore(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcRawRestResponseStore fromDriverManager(String url, String user, String password) {
        return new JdbcRawRestResponseStore(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public void insertRawRestResponseBatch(List<RawRestDbResponse> responses) throws Exception {
        Objects.requireNonNull(responses, "responses");
        if (responses.isEmpty()) {
            return;
        }

        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Exception failure = null;
            try {
                try (PreparedStatement statement = connection.prepareStatement(RAW_REST_INSERT_SQL)) {
                    for (RawRestDbResponse response : responses) {
                        bindResponse(statement, response);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (Exception e) {
                failure = e;
                rollback(connection, e);
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException e) {
                    if (failure == null) {
                        throw e;
                    }
                    failure.addSuppressed(e);
                }
            }
        }
    }

    private static void bindResponse(PreparedStatement statement, RawRestDbResponse response) throws SQLException {
        Objects.requireNonNull(response, "response");
        statement.setString(1, response.rawRestResponseId());
        statement.setString(2, response.endpoint());
        statement.setString(3, response.ticker());
        statement.setLong(4, response.fetchTsNs());
        statement.setObject(
            5,
            OffsetDateTime.ofInstant(Objects.requireNonNull(response.fetchWallTs(), "fetchWallTs"), ZoneOffset.UTC)
        );
        statement.setString(6, response.payloadSha256());
        statement.setString(7, response.rawPayload());
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            cause.addSuppressed(rollbackException);
        }
    }
}
