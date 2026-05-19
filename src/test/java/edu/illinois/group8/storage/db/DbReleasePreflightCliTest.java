package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbReleasePreflightCliTest {
    @Test
    void skipsWhenDbUrlEmptyAndNotRequired() {
        Output output = new Output();

        int exitCode = DbReleasePreflightCli.run(new String[0], Map.of(), output.out(), output.err());

        assertEquals(0, exitCode);
        assertTrue(output.stdout().contains("SKIP db_release_preflight"));
    }

    @Test
    void failsWhenRequiredAndDbUrlEmpty() {
        Output output = new Output();

        int exitCode = DbReleasePreflightCli.run(
            new String[0],
            Map.of("DEPLOY_DB_PREFLIGHT_REQUIRED", "true"),
            output.out(),
            output.err()
        );

        assertEquals(2, exitCode);
        assertTrue(output.stderr().contains("required=true"));
    }

    @Test
    void cliArgsOverrideEnvironment() {
        DbReleasePreflightCli.Config config = DbReleasePreflightCli.Config.from(
            new String[] {
                "--db-url=jdbc:postgresql://cli/db",
                "--db-user=cli-user",
                "--db-password=cli-password",
                "--required=true"
            },
            Map.of(
                "DB_WRITER_DATABASE_URL", "jdbc:postgresql://env/db",
                "DB_WRITER_DATABASE_USER", "env-user",
                "DB_WRITER_DATABASE_PASSWORD", "env-password",
                "DEPLOY_DB_PREFLIGHT_REQUIRED", "false"
            )
        );

        assertEquals("jdbc:postgresql://cli/db", config.dbUrl());
        assertEquals("cli-user", config.dbUser());
        assertEquals("cli-password", config.dbPassword());
        assertTrue(config.required());
    }

    @Test
    void passesForPostgres15SuccessfulV008AndReplayIndex() {
        Output output = new Output();
        RecordingJdbc jdbc = RecordingJdbc.pass();

        int exitCode = DbReleasePreflightCli.run(
            config(),
            jdbc::openConnection,
            output.out(),
            output.err()
        );

        assertEquals(0, exitCode);
        assertEquals(1, jdbc.openConnections);
        assertTrue(output.stdout().contains("PASS db_release_preflight"));
        assertTrue(output.stdout().contains("postgres_version_num=150000"));
        assertTrue(output.stdout().contains("canonical_replay_index=ok"));
    }

    @Test
    void rejectsPostgresVersionBefore15() {
        Output output = new Output();
        RecordingJdbc jdbc = RecordingJdbc.pass().withRows(
            DbReleasePreflightCheck.POSTGRES_VERSION_SQL,
            List.<Object[]>of(row("140000"))
        );

        int exitCode = DbReleasePreflightCli.run(config(), jdbc::openConnection, output.out(), output.err());

        assertEquals(2, exitCode);
        assertTrue(output.stderr().contains("server_version_num"));
        assertTrue(output.stderr().contains("actual=140000"));
    }

    @Test
    void rejectsMissingSuccessfulV008Migration() {
        Output output = new Output();
        RecordingJdbc jdbc = RecordingJdbc.pass().withRows(
            DbReleasePreflightCheck.FLYWAY_V008_SQL,
            List.of()
        );

        int exitCode = DbReleasePreflightCli.run(config(), jdbc::openConnection, output.out(), output.err());

        assertEquals(2, exitCode);
        assertTrue(output.stderr().contains("V008"));
    }

    @Test
    void rejectsMissingReplayIdentityIndex() {
        Output output = new Output();
        RecordingJdbc jdbc = RecordingJdbc.pass().withRows(
            DbReleasePreflightCheck.CANONICAL_REPLAY_INDEX_SQL,
            List.of()
        );

        int exitCode = DbReleasePreflightCli.run(config(), jdbc::openConnection, output.out(), output.err());

        assertEquals(2, exitCode);
        assertTrue(output.stderr().contains("canonical_events_event_replay_id_uidx"));
        assertTrue(output.stderr().contains("<missing>"));
    }

    @Test
    void rejectsReplayIndexWithoutNullsNotDistinct() {
        Output output = new Output();
        RecordingJdbc jdbc = RecordingJdbc.pass().withRows(
            DbReleasePreflightCheck.CANONICAL_REPLAY_INDEX_SQL,
            List.<Object[]>of(row("""
                CREATE UNIQUE INDEX canonical_events_event_replay_id_uidx
                ON public.canonical_events USING btree (event_id, replay_id)
                """))
        );

        int exitCode = DbReleasePreflightCli.run(config(), jdbc::openConnection, output.out(), output.err());

        assertEquals(2, exitCode);
        assertTrue(output.stderr().contains("NULLS NOT DISTINCT"));
    }

    private static DbReleasePreflightCli.Config config() {
        return new DbReleasePreflightCli.Config("jdbc:postgresql://db/kalshi", "user", "password", true, false);
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static final class Output {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        private PrintStream out() {
            return new PrintStream(stdout, true, StandardCharsets.UTF_8);
        }

        private PrintStream err() {
            return new PrintStream(stderr, true, StandardCharsets.UTF_8);
        }

        private String stdout() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        private String stderr() {
            return stderr.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class RecordingJdbc {
        private final Map<String, List<Object[]>> rowsBySql = new HashMap<>();
        private int openConnections;

        private static RecordingJdbc pass() {
            RecordingJdbc jdbc = new RecordingJdbc();
            jdbc.withRows(DbReleasePreflightCheck.POSTGRES_VERSION_SQL, List.<Object[]>of(row("150000")));
            jdbc.withRows(DbReleasePreflightCheck.FLYWAY_V008_SQL, List.<Object[]>of(row(true)));
            jdbc.withRows(
                DbReleasePreflightCheck.CANONICAL_REPLAY_INDEX_SQL,
                List.<Object[]>of(row("""
                    CREATE UNIQUE INDEX canonical_events_event_replay_id_uidx
                    ON public.canonical_events USING btree (event_id, replay_id) NULLS NOT DISTINCT
                    """))
            );
            return jdbc;
        }

        private RecordingJdbc withRows(String sql, List<Object[]> rows) {
            rowsBySql.put(normalizeSql(sql), List.copyOf(rows));
            return this;
        }

        private Connection openConnection() {
            openConnections++;
            InvocationHandler handler = this::handleConnectionInvocation;
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                handler
            );
        }

        private Object handleConnectionInvocation(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "createStatement" -> statement();
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private Statement statement() {
            InvocationHandler handler = this::handleStatementInvocation;
            return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                handler
            );
        }

        private Object handleStatementInvocation(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "executeQuery" -> {
                    String sql = normalizeSql((String) args[0]);
                    List<Object[]> rows = rowsBySql.get(sql);
                    if (rows == null) {
                        throw new SQLException("unexpected SQL: " + args[0]);
                    }
                    yield resultSet(rows);
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet(List<Object[]> rows) {
            InvocationHandler handler = new ResultSetHandler(rows);
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                handler
            );
        }

        private static String normalizeSql(String sql) {
            return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        }
    }

    private static final class ResultSetHandler implements InvocationHandler {
        private final List<Object[]> rows;
        private int index = -1;

        private ResultSetHandler(List<Object[]> rows) {
            this.rows = new ArrayList<>(rows);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> {
                    index++;
                    yield index < rows.size();
                }
                case "getString" -> {
                    Object value = valueAt((Integer) args[0]);
                    yield value == null ? null : value.toString();
                }
                case "getBoolean" -> {
                    Object value = valueAt((Integer) args[0]);
                    yield Boolean.TRUE.equals(value);
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object valueAt(int column) {
            return rows.get(index)[column - 1];
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
