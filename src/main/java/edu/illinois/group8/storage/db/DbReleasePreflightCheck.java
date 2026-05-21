package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;

public final class DbReleasePreflightCheck {
    static final int MIN_POSTGRES_VERSION_NUM = 150_000;
    static final String POSTGRES_VERSION_SQL = "select current_setting('server_version_num')";
    static final String FLYWAY_MIGRATION_SQL = """
        select success
        from flyway_schema_history
        where version in (?, ?)
        order by installed_rank desc
        limit 1
        """;
    static final String CANONICAL_REPLAY_INDEX_SQL = """
        select pg_get_indexdef(c.oid)
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
        where c.relkind = 'i'
          and c.relname = 'canonical_events_event_replay_id_uidx'
          and n.nspname = current_schema()
        """;

    private final JdbcConnectionFactory connectionFactory;

    public DbReleasePreflightCheck(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public DbReleasePreflightResult run() {
        try (Connection connection = connectionFactory.openConnection()) {
            int postgresVersion = postgresVersion(connection);
            if (postgresVersion < MIN_POSTGRES_VERSION_NUM) {
                throw new IllegalStateException(
                    "PostgreSQL server_version_num must be >= " + MIN_POSTGRES_VERSION_NUM
                        + "; actual=" + postgresVersion
                );
            }
            requireFlywayMigration(connection, "006");
            requireFlywayMigration(connection, "007");
            requireFlywayMigration(connection, "008");
            requireFlywayMigration(connection, "010");
            requireFlywayMigration(connection, "012");
            requireFlywayMigration(connection, "013");
            String indexDefinition = canonicalReplayIndexDefinition(connection);
            if (!validCanonicalReplayIndex(indexDefinition)) {
                throw new IllegalStateException(
                    "canonical_events_event_replay_id_uidx must be a unique index on "
                        + "(event_id, replay_id) NULLS NOT DISTINCT; actual="
                        + (indexDefinition == null ? "<missing>" : indexDefinition)
                );
            }
            return new DbReleasePreflightResult(postgresVersion, indexDefinition);
        } catch (SQLException exc) {
            throw new IllegalStateException("DB release preflight SQL failed: " + exc.getMessage(), exc);
        }
    }

    private int postgresVersion(Connection connection) throws SQLException {
        String raw = requiredString(connection, POSTGRES_VERSION_SQL, "PostgreSQL server_version_num");
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exc) {
            throw new IllegalStateException("Invalid PostgreSQL server_version_num: " + raw, exc);
        }
    }

    private void requireFlywayMigration(Connection connection, String version) throws SQLException {
        if (!flywayMigrationSucceeded(connection, version)) {
            throw new IllegalStateException(
                "flyway_schema_history does not contain successful V" + version + " migration."
            );
        }
    }

    private boolean flywayMigrationSucceeded(Connection connection, String paddedVersion) throws SQLException {
        String numericVersion = String.valueOf(Integer.parseInt(paddedVersion));
        try (
             PreparedStatement statement = connection.prepareStatement(FLYWAY_MIGRATION_SQL)) {
            statement.setString(1, numericVersion);
            statement.setString(2, paddedVersion);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                return resultSet.getBoolean(1);
            }
        }
    }

    private String canonicalReplayIndexDefinition(Connection connection) throws SQLException {
        return optionalString(connection, CANONICAL_REPLAY_INDEX_SQL);
    }

    private String requiredString(Connection connection, String sql, String label) throws SQLException {
        String value = optionalString(connection, sql);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " query returned no value.");
        }
        return value;
    }

    private String optionalString(Connection connection, String sql) throws SQLException {
        try (
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return null;
            }
            return resultSet.getString(1);
        }
    }

    static boolean validCanonicalReplayIndex(String indexDefinition) {
        if (indexDefinition == null || indexDefinition.isBlank()) {
            return false;
        }
        String normalized = indexDefinition
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ")
            .trim();
        return normalized.contains("unique index")
            && normalized.contains("canonical_events_event_replay_id_uidx")
            && normalized.contains("canonical_events")
            && normalized.contains("(event_id, replay_id)")
            && normalized.contains("nulls not distinct");
    }
}
