package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;

public final class JdbcFeatureOutputStore implements FeatureOutputStore {
    static final String INSERT_SQL = """
        insert into feature_outputs (
            feature_event_id,
            source_event_id,
            feature_name,
            feature_version,
            market_ticker,
            event_ts_ms,
            "values"
        ) values (?, ?, ?, ?, ?, ?, ?::jsonb)
        on conflict do nothing
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcFeatureOutputStore(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcFeatureOutputStore fromDriverManager(String url, String user, String password) {
        return new JdbcFeatureOutputStore(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public void insertFeatureOutput(FeatureOutputDbEvent output) throws Exception {
        Objects.requireNonNull(output, "output");

        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            bindOutput(statement, output);
            statement.executeUpdate();
        }
    }

    @Override
    public void insertFeatureOutputBatch(List<FeatureOutputDbEvent> outputs) throws Exception {
        Objects.requireNonNull(outputs, "outputs");
        if (outputs.isEmpty()) {
            return;
        }

        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Exception failure = null;
            try {
                try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                    for (FeatureOutputDbEvent output : outputs) {
                        bindOutput(statement, Objects.requireNonNull(output, "output"));
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

    static void bindOutput(PreparedStatement statement, FeatureOutputDbEvent output) throws SQLException {
        statement.setString(1, output.featureEventId());
        statement.setString(2, output.sourceEventId());
        statement.setString(3, output.featureName());
        statement.setInt(4, output.featureVersion());
        statement.setString(5, output.marketTicker());
        setNullableLong(statement, 6, output.eventTsMs());
        statement.setString(7, output.values());
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            cause.addSuppressed(rollbackException);
        }
    }
}
