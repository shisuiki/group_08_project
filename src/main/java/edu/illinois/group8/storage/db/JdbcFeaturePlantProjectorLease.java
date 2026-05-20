package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

public final class JdbcFeaturePlantProjectorLease implements FeaturePlantProjectorLease {
    static final String TRY_LOCK_SQL = """
        select pg_try_advisory_lock(hashtextextended(?::text, 0::bigint))
        """;

    static final String UNLOCK_SQL = """
        select pg_advisory_unlock(hashtextextended(?::text, 0::bigint))
        """;
    static final String CHECK_HELD_SQL = "select 1";

    private final Connection connection;
    private final String cursorName;
    private boolean closed;

    private JdbcFeaturePlantProjectorLease(Connection connection, String cursorName) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.cursorName = JdbcFeaturePlantCursorStore.normalizeCursorName(cursorName);
    }

    public static JdbcFeaturePlantProjectorLease acquire(JdbcConnectionFactory connectionFactory, String cursorName) {
        Objects.requireNonNull(connectionFactory, "connectionFactory");
        String normalizedName = JdbcFeaturePlantCursorStore.normalizeCursorName(cursorName);
        Connection connection = null;
        try {
            connection = connectionFactory.openConnection();
            boolean acquired = tryAcquire(connection, normalizedName);
            if (!acquired) {
                closeQuietly(connection);
                connection = null;
                throw new IllegalStateException(
                    "FeaturePlant DB projector cursor lock is already held: " + normalizedName
                );
            }
            return new JdbcFeaturePlantProjectorLease(connection, normalizedName);
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("Failed to acquire FeaturePlant DB projector cursor lock " + normalizedName, e);
        } catch (RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    @Override
    public void ensureHeld() {
        if (closed) {
            throw new IllegalStateException("FeaturePlant DB projector cursor lock is closed: " + cursorName);
        }
        try (PreparedStatement statement = connection.prepareStatement(CHECK_HELD_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IllegalStateException("FeaturePlant DB projector cursor lock health check returned no rows: "
                    + cursorName);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("FeaturePlant DB projector cursor lock is no longer held: " + cursorName, e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        SQLException failure = null;
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
            statement.setString(1, cursorName);
            try (ResultSet ignored = statement.executeQuery()) {
                // The unlock result is not needed; holding the result set open until close keeps cleanup explicit.
            }
        } catch (SQLException e) {
            failure = e;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw new IllegalStateException("Failed to release FeaturePlant DB projector cursor lock " + cursorName,
                failure);
        }
    }

    private static boolean tryAcquire(Connection connection, String cursorName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
            statement.setString(1, cursorName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Best effort cleanup after a failed lock acquisition.
        }
    }
}
