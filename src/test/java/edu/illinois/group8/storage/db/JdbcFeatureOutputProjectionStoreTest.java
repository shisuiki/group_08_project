package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcFeatureOutputProjectionStoreTest {
    @Test
    void loadCursorUsesCursorStoreSelectSql() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.loadCursorValue = 42L;
        JdbcFeatureOutputProjectionStore store = new JdbcFeatureOutputProjectionStore(jdbc::openConnection);

        Optional<CanonicalDbCursor> cursor = store.loadCursor(" featureplant-prod ");

        assertTrue(cursor.isPresent());
        assertEquals(42L, cursor.orElseThrow().lastCommitSeq());
        assertEquals(List.of(JdbcFeaturePlantCursorStore.SELECT_SQL), jdbc.preparedSqls);
        assertEquals("featureplant-prod", jdbc.statementParameters.get(0).get(1));
    }

    @Test
    void commitProjectionInsertsOutputsAndCursorInSingleTransaction() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcFeatureOutputProjectionStore store = new JdbcFeatureOutputProjectionStore(jdbc::openConnection);

        store.commitProjection(
            "featureplant-prod",
            new CanonicalDbCursor(8L),
            List.of(
                new FeatureOutputDbEvent("feature-1", "source-1", "feature.bbo", 1, "M", 11L, "{\"x\":1}"),
                new FeatureOutputDbEvent("feature-2", null, "feature.trade_tape", 1, null, null, "{}")
            )
        );

        assertEquals(1, jdbc.openConnections);
        assertEquals(List.of(false, true), jdbc.autoCommitSetValues);
        assertEquals(1, jdbc.executeBatchCalls);
        assertEquals(1, jdbc.executeUpdateCalls);
        assertEquals(1, jdbc.commitCalls);
        assertEquals(0, jdbc.rollbackCalls);
        assertEquals(List.of(
            JdbcFeatureOutputStore.INSERT_SQL,
            JdbcFeaturePlantCursorStore.UPSERT_SQL
        ), jdbc.preparedSqls);
        assertEquals(2, jdbc.batchParameters.size());
        assertEquals("feature-1", jdbc.batchParameters.get(0).get(1));
        assertEquals("source-1", jdbc.batchParameters.get(0).get(2));
        assertEquals("{\"x\":1}", jdbc.batchParameters.get(0).get(7));
        assertEquals("feature-2", jdbc.batchParameters.get(1).get(1));
        assertEquals(new SqlNull(Types.BIGINT), jdbc.batchParameters.get(1).get(6));
        Map<Integer, Object> cursorParams = jdbc.statementParameters.get(1);
        assertEquals("featureplant-prod", cursorParams.get(1));
        assertEquals(8L, cursorParams.get(2));
    }

    @Test
    void commitProjectionAdvancesCursorWhenNoOutputsAreProduced() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcFeatureOutputProjectionStore store = new JdbcFeatureOutputProjectionStore(jdbc::openConnection);

        store.commitProjection("featureplant-prod", new CanonicalDbCursor(9L), List.of());

        assertEquals(List.of(JdbcFeaturePlantCursorStore.UPSERT_SQL), jdbc.preparedSqls);
        assertEquals(0, jdbc.executeBatchCalls);
        assertEquals(1, jdbc.executeUpdateCalls);
        assertEquals(1, jdbc.commitCalls);
        assertEquals(0, jdbc.rollbackCalls);
        assertEquals("featureplant-prod", jdbc.statementParameters.get(0).get(1));
        assertEquals(9L, jdbc.statementParameters.get(0).get(2));
    }

    @Test
    void commitProjectionUpsertsLatestStatesBeforeCursorInSameTransaction() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcFeatureOutputProjectionStore store = new JdbcFeatureOutputProjectionStore(jdbc::openConnection);

        store.commitProjection(
            "featureplant-prod",
            new CanonicalDbCursor(11L),
            List.of(new FeatureOutputDbEvent("feature-1", "source-1", "feature.bbo", 1, "M", 11L, "{\"x\":1}")),
            List.of(new LatestMarketState("M", 11L, "source-1", 11L, 100L, 200L, 150L, null, "{\"x\":1}"))
        );

        assertEquals(List.of(
            JdbcFeatureOutputStore.INSERT_SQL,
            JdbcLatestMarketStateStore.UPSERT_SQL,
            JdbcFeaturePlantCursorStore.UPSERT_SQL
        ), jdbc.preparedSqls);
        assertEquals(2, jdbc.executeBatchCalls);
        assertEquals(1, jdbc.executeUpdateCalls);
        assertEquals(1, jdbc.commitCalls);
        assertEquals(0, jdbc.rollbackCalls);
        assertEquals("feature-1", jdbc.batchParameters.get(0).get(1));
        assertEquals("M", jdbc.batchParameters.get(1).get(1));
        assertEquals(11L, jdbc.batchParameters.get(1).get(4));
        assertEquals("{\"x\":1}", jdbc.batchParameters.get(1).get(9));
        Map<Integer, Object> cursorParams = jdbc.statementParameters.get(2);
        assertEquals("featureplant-prod", cursorParams.get(1));
        assertEquals(11L, cursorParams.get(2));
    }

    @Test
    void commitProjectionRollsBackWhenCursorUpsertFails() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.executeUpdateFailure = new SQLException("cursor failed");
        JdbcFeatureOutputProjectionStore store = new JdbcFeatureOutputProjectionStore(jdbc::openConnection);

        assertThrows(Exception.class, () -> store.commitProjection(
            "featureplant-prod",
            new CanonicalDbCursor(10L),
            List.of(new FeatureOutputDbEvent("feature-1", "source-1", "feature.bbo", 1, "M", 11L, "{}"))
        ));

        assertEquals(1, jdbc.executeBatchCalls);
        assertEquals(1, jdbc.executeUpdateCalls);
        assertEquals(0, jdbc.commitCalls);
        assertEquals(1, jdbc.rollbackCalls);
        assertEquals(List.of(false, true), jdbc.autoCommitSetValues);
    }

    @Test
    void commitProjectionRollsBackWhenOutputBatchFailsBeforeCursorUpsert() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.executeBatchFailure = new SQLException("output failed");
        JdbcFeatureOutputProjectionStore store = new JdbcFeatureOutputProjectionStore(jdbc::openConnection);

        assertThrows(Exception.class, () -> store.commitProjection(
            "featureplant-prod",
            new CanonicalDbCursor(10L),
            List.of(new FeatureOutputDbEvent("feature-1", "source-1", "feature.bbo", 1, "M", 11L, "{}"))
        ));

        assertEquals(1, jdbc.executeBatchCalls);
        assertEquals(0, jdbc.executeUpdateCalls);
        assertEquals(0, jdbc.commitCalls);
        assertEquals(1, jdbc.rollbackCalls);
        assertEquals(List.of(false, true), jdbc.autoCommitSetValues);
    }

    @Test
    void commitProjectionRollsBackWhenLatestStateBatchFailsBeforeCursorUpsert() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.executeBatchFailure = new SQLException("latest state failed");
        jdbc.executeBatchFailureOnCall = 2;
        JdbcFeatureOutputProjectionStore store = new JdbcFeatureOutputProjectionStore(jdbc::openConnection);

        assertThrows(Exception.class, () -> store.commitProjection(
            "featureplant-prod",
            new CanonicalDbCursor(10L),
            List.of(new FeatureOutputDbEvent("feature-1", "source-1", "feature.bbo", 1, "M", 11L, "{}")),
            List.of(new LatestMarketState("M", 11L, "source-1", 10L, 100L, 200L, 150L, null, "{}"))
        ));

        assertEquals(2, jdbc.executeBatchCalls);
        assertEquals(0, jdbc.executeUpdateCalls);
        assertEquals(0, jdbc.commitCalls);
        assertEquals(1, jdbc.rollbackCalls);
        assertEquals(List.of(false, true), jdbc.autoCommitSetValues);
    }

    private record SqlNull(int sqlType) {
    }

    private static final class RecordingJdbc {
        private int openConnections;
        private int executeBatchCalls;
        private int executeUpdateCalls;
        private int commitCalls;
        private int rollbackCalls;
        private Long loadCursorValue;
        private SQLException executeBatchFailure;
        private int executeBatchFailureOnCall;
        private SQLException executeUpdateFailure;
        private final List<String> preparedSqls = new ArrayList<>();
        private final List<Boolean> autoCommitSetValues = new ArrayList<>();
        private final List<Map<Integer, Object>> statementParameters = new ArrayList<>();
        private final List<Map<Integer, Object>> batchParameters = new ArrayList<>();

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
                case "getAutoCommit" -> true;
                case "setAutoCommit" -> {
                    autoCommitSetValues.add((Boolean) args[0]);
                    yield null;
                }
                case "commit" -> {
                    commitCalls++;
                    yield null;
                }
                case "rollback" -> {
                    rollbackCalls++;
                    yield null;
                }
                case "prepareStatement" -> {
                    String sql = (String) args[0];
                    preparedSqls.add(sql);
                    Map<Integer, Object> parameters = new HashMap<>();
                    statementParameters.add(parameters);
                    yield preparedStatement(sql, parameters);
                }
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement(String sql, Map<Integer, Object> parameters) {
            InvocationHandler handler = (proxy, method, args) -> handlePreparedStatementInvocation(
                sql,
                parameters,
                method,
                args
            );
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                handler
            );
        }

        private Object handlePreparedStatementInvocation(
            String sql,
            Map<Integer, Object> parameters,
            Method method,
            Object[] args
        ) throws Throwable {
            return switch (method.getName()) {
                case "setString", "setInt", "setLong" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setNull" -> {
                    parameters.put((Integer) args[0], new SqlNull((Integer) args[1]));
                    yield null;
                }
                case "addBatch" -> {
                    batchParameters.add(new HashMap<>(parameters));
                    yield null;
                }
                case "executeBatch" -> {
                    executeBatchCalls++;
                    if (executeBatchFailure != null
                        && (executeBatchFailureOnCall == 0 || executeBatchCalls == executeBatchFailureOnCall)) {
                        throw executeBatchFailure;
                    }
                    yield new int[batchParameters.size()];
                }
                case "executeUpdate" -> {
                    executeUpdateCalls++;
                    if (executeUpdateFailure != null) {
                        throw executeUpdateFailure;
                    }
                    yield 1;
                }
                case "executeQuery" -> resultSet();
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet() {
            InvocationHandler handler = new InvocationHandler() {
                private boolean beforeFirst = true;

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "next" -> {
                            boolean hasNext = beforeFirst && loadCursorValue != null;
                            beforeFirst = false;
                            yield hasNext;
                        }
                        case "getObject" -> loadCursorValue;
                        case "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    };
                }
            };
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                handler
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
}
