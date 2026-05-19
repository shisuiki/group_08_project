package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class JdbcFeaturePlantCursorStore implements FeaturePlantCursorStore {
    static final String SELECT_SQL = """
        select last_commit_seq
        from featureplant_cursors
        where cursor_name = ?
        """;

    static final String UPSERT_SQL = """
        insert into featureplant_cursors (
            cursor_name,
            last_commit_seq
        ) values (?, ?)
        on conflict (cursor_name) do update set
            last_commit_seq = greatest(featureplant_cursors.last_commit_seq, excluded.last_commit_seq),
            updated_at = now()
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcFeaturePlantCursorStore(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcFeaturePlantCursorStore fromDriverManager(String url, String user, String password) {
        return new JdbcFeaturePlantCursorStore(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public Optional<CanonicalDbCursor> loadCursor(String cursorName) {
        String normalizedName = normalizeCursorName(cursorName);
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
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
    public void saveCursor(String cursorName, CanonicalDbCursor cursor) {
        String normalizedName = normalizeCursorName(cursorName);
        Objects.requireNonNull(cursor, "cursor");
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            statement.setString(1, normalizedName);
            statement.setLong(2, cursor.lastCommitSeq());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save featureplant cursor " + normalizedName, e);
        }
    }

    static String normalizeCursorName(String cursorName) {
        if (cursorName == null || cursorName.isBlank()) {
            throw new IllegalArgumentException("cursorName must be non-blank");
        }
        return cursorName.trim();
    }

    private static long longValue(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
