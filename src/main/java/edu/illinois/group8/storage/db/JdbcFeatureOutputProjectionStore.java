package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcFeatureOutputProjectionStore implements FeatureOutputProjectionStore {
    private final JdbcConnectionFactory connectionFactory;

    public JdbcFeatureOutputProjectionStore(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcFeatureOutputProjectionStore fromDriverManager(String url, String user, String password) {
        return new JdbcFeatureOutputProjectionStore(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public Optional<CanonicalDbCursor> loadCursor(String cursorName) {
        String normalizedName = JdbcFeaturePlantCursorStore.normalizeCursorName(cursorName);
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(JdbcFeaturePlantCursorStore.SELECT_SQL)) {
            statement.setString(1, normalizedName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CanonicalDbCursor(longValue(resultSet, "last_commit_seq")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load featureplant cursor " + normalizedName, e);
        }
    }

    @Override
    public void commitProjection(String cursorName, CanonicalDbCursor cursor, List<FeatureOutputDbEvent> outputs)
        throws Exception {
        String normalizedName = JdbcFeaturePlantCursorStore.normalizeCursorName(cursorName);
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(outputs, "outputs");

        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Exception failure = null;
            try {
                insertOutputs(connection, outputs);
                upsertCursor(connection, normalizedName, cursor);
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

    private static void insertOutputs(Connection connection, List<FeatureOutputDbEvent> outputs) throws SQLException {
        if (outputs.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(JdbcFeatureOutputStore.INSERT_SQL)) {
            for (FeatureOutputDbEvent output : outputs) {
                JdbcFeatureOutputStore.bindOutput(statement, Objects.requireNonNull(output, "output"));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void upsertCursor(Connection connection, String cursorName, CanonicalDbCursor cursor)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(JdbcFeaturePlantCursorStore.UPSERT_SQL)) {
            statement.setString(1, cursorName);
            statement.setLong(2, cursor.lastCommitSeq());
            statement.executeUpdate();
        }
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            cause.addSuppressed(rollbackException);
        }
    }

    private static long longValue(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
