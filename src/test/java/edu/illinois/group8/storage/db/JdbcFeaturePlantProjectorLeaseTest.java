package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcFeaturePlantProjectorLeaseTest {
    @Test
    void acquireHoldsConnectionUntilCloseAndReleasesAdvisoryLock() {
        RecordingJdbc jdbc = new RecordingJdbc(true);

        JdbcFeaturePlantProjectorLease lease = JdbcFeaturePlantProjectorLease.acquire(
            jdbc::openConnection,
            " featureplant-prod "
        );

        assertEquals(1, jdbc.openConnections);
        assertEquals(0, jdbc.connectionCloseCalls);
        assertEquals(List.of(JdbcFeaturePlantProjectorLease.TRY_LOCK_SQL), jdbc.preparedSqls);
        assertEquals("featureplant-prod", jdbc.statementParameters.get(0).get(1));

        lease.close();
        lease.close();

        assertEquals(List.of(
            JdbcFeaturePlantProjectorLease.TRY_LOCK_SQL,
            JdbcFeaturePlantProjectorLease.UNLOCK_SQL
        ), jdbc.preparedSqls);
        assertEquals("featureplant-prod", jdbc.statementParameters.get(1).get(1));
        assertEquals(1, jdbc.connectionCloseCalls);
    }

    @Test
    void ensureHeldUsesHeldConnectionAndFailsWhenLeaseConnectionBreaks() {
        RecordingJdbc jdbc = new RecordingJdbc(true);
        JdbcFeaturePlantProjectorLease lease = JdbcFeaturePlantProjectorLease.acquire(
            jdbc::openConnection,
            "featureplant-prod"
        );

        lease.ensureHeld();

        assertEquals(1, jdbc.openConnections);
        assertEquals(0, jdbc.connectionCloseCalls);
        assertEquals(List.of(
            JdbcFeaturePlantProjectorLease.TRY_LOCK_SQL,
            JdbcFeaturePlantProjectorLease.CHECK_HELD_SQL
        ), jdbc.preparedSqls.subList(0, 2));

        jdbc.failHealthCheck = true;
        IllegalStateException thrown = assertThrows(IllegalStateException.class, lease::ensureHeld);
        assertTrue(thrown.getMessage().contains("lock is no longer held"));

        lease.close();
        assertEquals(1, jdbc.connectionCloseCalls);
    }

    @Test
    void acquireFailsFastWhenCursorLockIsHeldByAnotherProjector() {
        RecordingJdbc jdbc = new RecordingJdbc(false);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> JdbcFeaturePlantProjectorLease.acquire(jdbc::openConnection, "featureplant-prod")
        );

        assertTrue(thrown.getMessage().contains("cursor lock is already held"));
        assertEquals(1, jdbc.openConnections);
        assertEquals(1, jdbc.connectionCloseCalls);
        assertEquals(List.of(JdbcFeaturePlantProjectorLease.TRY_LOCK_SQL), jdbc.preparedSqls);
    }

    @Test
    void acquireValidatesCursorName() {
        RecordingJdbc jdbc = new RecordingJdbc(true);

        assertThrows(IllegalArgumentException.class, () -> JdbcFeaturePlantProjectorLease.acquire(
            jdbc::openConnection,
            " "
        ));

        assertEquals(0, jdbc.openConnections);
    }

    private static final class RecordingJdbc {
        private final boolean tryLockResult;
        private final List<String> preparedSqls = new ArrayList<>();
        private final List<Map<Integer, Object>> statementParameters = new ArrayList<>();
        private int openConnections;
        private int connectionCloseCalls;
        private boolean failHealthCheck;

        private RecordingJdbc(boolean tryLockResult) {
            this.tryLockResult = tryLockResult;
        }

        private Connection openConnection() {
            openConnections++;
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        preparedSqls.add(sql);
                        Map<Integer, Object> parameters = new HashMap<>();
                        statementParameters.add(parameters);
                        yield preparedStatement(sql, parameters);
                    }
                    case "close" -> {
                        connectionCloseCalls++;
                        yield null;
                    }
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                }
            );
        }

        private PreparedStatement preparedStatement(String sql, Map<Integer, Object> parameters) {
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> handlePreparedStatementInvocation(sql, parameters, method, args)
            );
        }

        private Object handlePreparedStatementInvocation(
            String sql,
            Map<Integer, Object> parameters,
            Method method,
            Object[] args
        ) throws SQLException {
            return switch (method.getName()) {
                case "setString" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "executeQuery" -> {
                    if (sameSql(sql, JdbcFeaturePlantProjectorLease.CHECK_HELD_SQL) && failHealthCheck) {
                        throw new SQLException("connection lost");
                    }
                    yield resultSet(sql);
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet(String sql) {
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                new java.lang.reflect.InvocationHandler() {
                    private boolean beforeFirst = true;

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return switch (method.getName()) {
                            case "next" -> {
                                boolean hasNext = beforeFirst;
                                beforeFirst = false;
                                yield hasNext;
                            }
                            case "getBoolean" -> sameSql(sql, JdbcFeaturePlantProjectorLease.TRY_LOCK_SQL)
                                && tryLockResult;
                            case "close" -> null;
                            default -> defaultValue(method.getReturnType());
                        };
                    }
                }
            );
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
            return 0;
        }

        private static boolean sameSql(String left, String right) {
            return left.replaceAll("\\s+", " ").trim().equals(right.replaceAll("\\s+", " ").trim());
        }
    }
}
