package edu.illinois.group8.storage.db;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAcceptedEventStoreTest {
    @Test
    void emptyBatchesDoNotOpenConnection() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcAcceptedEventStore store = new JdbcAcceptedEventStore(jdbc::openConnection);

        store.insertRawBatch(List.of());
        store.insertCanonicalBatch(List.of());

        assertEquals(0, jdbc.openConnections);
    }

    @Test
    void sqlConstantsUsePostgresJsonbAndConflictIgnore() {
        String rawSql = JdbcAcceptedEventStore.RAW_INSERT_SQL.toLowerCase(Locale.ROOT);
        String canonicalSql = JdbcAcceptedEventStore.CANONICAL_INSERT_SQL.toLowerCase(Locale.ROOT);

        assertTrue(rawSql.contains("?::jsonb"));
        assertTrue(rawSql.contains("on conflict do nothing"));
        assertTrue(canonicalSql.contains("?::jsonb"));
        assertTrue(canonicalSql.contains("on conflict do nothing"));
        assertFalse(canonicalSql.contains("foreign key"));
    }

    @Test
    void rawBatchBindsDefaultsAndCommits() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcAcceptedEventStore store = new JdbcAcceptedEventStore(jdbc::openConnection);
        RawWsDbEvent event = new RawWsDbEvent(
            "raw-1",
            "kalshi-ws",
            "capture-1",
            "connection-1",
            11L,
            22L,
            Instant.parse("2026-05-19T00:00:00Z"),
            "MARKET-1",
            "ticker",
            null,
            "sha256",
            "{\"type\":\"ticker\"}",
            " "
        );

        store.insertRawBatch(List.of(event));

        assertEquals(1, jdbc.openConnections);
        assertEquals(1, jdbc.commitCalls);
        assertEquals(0, jdbc.rollbackCalls);
        assertEquals(JdbcAcceptedEventStore.RAW_INSERT_SQL, jdbc.preparedSql);
        assertEquals(List.of(false, true), jdbc.autoCommitValues);
        Map<Integer, Object> row = jdbc.batches.get(0);
        assertEquals("raw-1", row.get(1));
        assertInstanceOf(OffsetDateTime.class, row.get(7));
        assertEquals(new SqlNull(Types.BIGINT), row.get(10));
        assertEquals("{\"type\":\"ticker\"}", row.get(12));
        assertEquals("stored", row.get(13));
    }

    @Test
    void canonicalBatchBindsNullableFieldsAndCommits() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcAcceptedEventStore store = new JdbcAcceptedEventStore(jdbc::openConnection);
        CanonicalDbEvent event = new CanonicalDbEvent(
            "event-1",
            null,
            "replay-1",
            "canonical.ticker",
            "ticker",
            1,
            "MARKET-1",
            null,
            123L,
            null,
            "{\"market_ticker\":\"MARKET-1\"}"
        );

        store.insertCanonicalBatch(List.of(event));

        assertEquals(1, jdbc.openConnections);
        assertEquals(1, jdbc.commitCalls);
        assertEquals(0, jdbc.rollbackCalls);
        assertEquals(JdbcAcceptedEventStore.CANONICAL_INSERT_SQL, jdbc.preparedSql);
        Map<Integer, Object> row = jdbc.batches.get(0);
        assertEquals("event-1", row.get(1));
        assertEquals(null, row.get(2));
        assertEquals("replay-1", row.get(3));
        assertEquals(new SqlNull(Types.BIGINT), row.get(8));
        assertEquals(123L, row.get(9));
        assertEquals(new SqlNull(Types.BIGINT), row.get(10));
        assertEquals("{\"market_ticker\":\"MARKET-1\"}", row.get(11));
    }

    @Test
    void failedBatchRollsBackAndRethrows() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.failExecute = true;
        JdbcAcceptedEventStore store = new JdbcAcceptedEventStore(jdbc::openConnection);

        SQLException thrown = assertThrows(
            SQLException.class,
            () -> store.insertRawBatch(List.of(rawEvent("raw-fail")))
        );

        assertEquals("execute failed", thrown.getMessage());
        assertEquals(0, jdbc.commitCalls);
        assertEquals(1, jdbc.rollbackCalls);
    }

    @Test
    void integrationDuplicateInsertsAreIdempotentWhenEnabled() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("KALSHI_DB_INTEGRATION_TESTS")));
        String url = System.getenv("KALSHI_DB_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "KALSHI_DB_TEST_URL is required");
        assumeSafeIntegrationDatabase(url);
        String user = System.getenv("KALSHI_DB_TEST_USER");
        String password = System.getenv("KALSHI_DB_TEST_PASSWORD");
        JdbcAcceptedEventStore store = JdbcAcceptedEventStore.fromDriverManager(url, user, password);
        String suffix = UUID.randomUUID().toString();
        RawWsDbEvent rawEvent = rawEvent("raw-" + suffix);
        CanonicalDbEvent canonicalEvent = canonicalEvent("canonical-" + suffix, rawEvent.rawEventId());

        try (Connection connection = openDriverManagerConnection(url, user, password)) {
            applyMigration(connection);
        }

        Throwable failure = null;
        try {
            store.insertRawBatch(List.of(rawEvent));
            store.insertRawBatch(List.of(rawEvent));
            store.insertCanonicalBatch(List.of(canonicalEvent));
            store.insertCanonicalBatch(List.of(canonicalEvent));

            try (Connection connection = openDriverManagerConnection(url, user, password)) {
                assertEquals(
                    1L,
                    countById(connection, "select count(*) from raw_ws_events where raw_event_id = ?", rawEvent.rawEventId())
                );
                assertEquals(
                    1L,
                    countById(
                        connection,
                        "select count(*) from canonical_events where event_id = ?",
                        canonicalEvent.eventId()
                    )
                );
            }
        } catch (Exception | AssertionError e) {
            failure = e;
            throw e;
        } finally {
            try {
                cleanupInsertedRows(url, user, password, rawEvent.rawEventId(), canonicalEvent.eventId());
            } catch (SQLException e) {
                if (failure == null) {
                    throw e;
                }
                failure.addSuppressed(e);
            }
        }
    }

    private static RawWsDbEvent rawEvent(String rawEventId) {
        return new RawWsDbEvent(
            rawEventId,
            "kalshi-ws",
            "capture-1",
            "connection-1",
            1L,
            2L,
            Instant.parse("2026-05-19T00:00:00Z"),
            "MARKET-1",
            "ticker",
            3L,
            "sha256-" + rawEventId,
            "{\"type\":\"ticker\"}",
            null
        );
    }

    private static CanonicalDbEvent canonicalEvent(String eventId, String rawEventId) {
        return new CanonicalDbEvent(
            eventId,
            rawEventId,
            null,
            "canonical.ticker",
            "ticker",
            1,
            "MARKET-1",
            10L,
            20L,
            30L,
            "{\"market_ticker\":\"MARKET-1\"}"
        );
    }

    private static Connection openDriverManagerConnection(String url, String user, String password) throws SQLException {
        if (user == null) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, user, password);
    }

    private static void assumeSafeIntegrationDatabase(String url) {
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        String databaseName = databaseName(lowerUrl);
        Assumptions.assumeFalse(
            lowerUrl.contains("prod") || lowerUrl.contains("production"),
            "KALSHI_DB_TEST_URL must not point at a production-like database"
        );
        Assumptions.assumeTrue(
            hasTestDatabaseMarker(databaseName),
            "KALSHI_DB_TEST_URL database name must be test, kalshi_test, or contain a separated test marker"
        );
    }

    private static boolean hasTestDatabaseMarker(String databaseName) {
        return databaseName.equals("test")
            || databaseName.equals("kalshi_test")
            || databaseName.startsWith("test_")
            || databaseName.startsWith("test-")
            || databaseName.endsWith("_test")
            || databaseName.endsWith("-test")
            || databaseName.contains("_test_")
            || databaseName.contains("-test-");
    }

    private static String databaseName(String lowerUrl) {
        int queryStart = lowerUrl.indexOf('?');
        String withoutQuery = queryStart >= 0 ? lowerUrl.substring(0, queryStart) : lowerUrl;
        int slash = withoutQuery.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < withoutQuery.length()) {
            return withoutQuery.substring(slash + 1);
        }
        int colon = withoutQuery.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < withoutQuery.length()) {
            return withoutQuery.substring(colon + 1);
        }
        return withoutQuery;
    }

    private static void applyMigration(Connection connection) throws Exception {
        String migration;
        try (InputStream inputStream = Objects.requireNonNull(
            JdbcAcceptedEventStoreTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V001__accepted_event_storage.sql"),
            "migration resource"
        )) {
            migration = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String statementSql : migration.split(";")) {
            String trimmed = statementSql.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(trimmed);
            }
        }
    }

    private static void cleanupInsertedRows(
        String url,
        String user,
        String password,
        String rawEventId,
        String canonicalEventId
    ) throws SQLException {
        try (Connection connection = openDriverManagerConnection(url, user, password)) {
            deleteById(connection, "delete from canonical_events where event_id = ?", canonicalEventId);
            deleteById(connection, "delete from raw_ws_events where raw_event_id = ?", rawEventId);
        }
    }

    private static void deleteById(Connection connection, String sql, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private static long countById(Connection connection, String sql, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private record SqlNull(int sqlType) {
    }

    private static final class RecordingJdbc {
        private int openConnections;
        private int commitCalls;
        private int rollbackCalls;
        private String preparedSql;
        private boolean autoCommit = true;
        private boolean failExecute;
        private final List<Boolean> autoCommitValues = new ArrayList<>();
        private final List<Map<Integer, Object>> batches = new ArrayList<>();

        private Connection openConnection() {
            openConnections++;
            InvocationHandler handler = this::handleConnectionInvocation;
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                handler
            );
        }

        private Object handleConnectionInvocation(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> {
                    autoCommit = (Boolean) args[0];
                    autoCommitValues.add(autoCommit);
                    yield null;
                }
                case "prepareStatement" -> {
                    preparedSql = (String) args[0];
                    yield preparedStatement();
                }
                case "commit" -> {
                    commitCalls++;
                    yield null;
                }
                case "rollback" -> {
                    rollbackCalls++;
                    yield null;
                }
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement() {
            Map<Integer, Object> parameters = new HashMap<>();
            InvocationHandler handler = (proxy, method, args) ->
                handlePreparedStatementInvocation(method, args, parameters);
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                handler
            );
        }

        private Object handlePreparedStatementInvocation(
            Method method,
            Object[] args,
            Map<Integer, Object> parameters
        ) throws SQLException {
            return switch (method.getName()) {
                case "setString", "setLong", "setInt", "setObject" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setNull" -> {
                    parameters.put((Integer) args[0], new SqlNull((Integer) args[1]));
                    yield null;
                }
                case "addBatch" -> {
                    batches.add(new HashMap<>(parameters));
                    parameters.clear();
                    yield null;
                }
                case "executeBatch" -> {
                    if (failExecute) {
                        throw new SQLException("execute failed");
                    }
                    int[] result = new int[batches.size()];
                    Arrays.fill(result, 1);
                    yield result;
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
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
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0F;
            }
            if (returnType == double.class) {
                return 0D;
            }
            if (returnType == char.class) {
                return '\0';
            }
            return null;
        }
    }
}
