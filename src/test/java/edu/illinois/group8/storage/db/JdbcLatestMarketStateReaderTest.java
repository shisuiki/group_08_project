package edu.illinois.group8.storage.db;

import edu.illinois.group8.feature.BestBidOfferFeatureModule;
import edu.illinois.group8.feature.FeatureOutput;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcLatestMarketStateReaderTest {
    @Test
    void implementsFeatureOutputReaderContractAndMapsLatestRows() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "market_ticker", "MKT-1",
            "last_event_ts_ms", 1000L,
            "last_canonical_event_id", "source-1",
            "last_canonical_commit_seq", 42L,
            "best_bid_micros", 440000L,
            "best_ask_micros", 470000L,
            "midpoint_micros", 455000L,
            "open_interest", null,
            "payload", "{\"crossed\":false,\"bid_quantity_micros\":1000000}",
            "updated_at", Instant.parse("2026-05-20T00:00:01Z")
        )));
        JdbcLatestMarketStateReader reader = new JdbcLatestMarketStateReader(jdbc::openConnection);

        assertInstanceOf(FeatureOutputReader.class, reader);
        List<FeatureOutput> outputs = reader.read(null);

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("from latest_market_state"));
        assertTrue(sql.contains(
            "order by last_canonical_commit_seq desc nulls last, updated_at desc, market_ticker asc"
        ));
        assertEquals(List.of(FeatureOutputReadRequest.DEFAULT_MAX_ROWS), jdbc.bindings);
        FeatureOutput output = outputs.get(0);
        assertEquals(BestBidOfferFeatureModule.FEATURE_NAME, output.featureName());
        assertEquals("MKT-1", output.marketTicker());
        assertEquals(1000L, output.eventTsMs());
        assertEquals("source-1", output.sourceEventId());
        assertEquals(440000L, ((Number) output.values().get("bid_price_micros")).longValue());
        assertEquals(470000L, ((Number) output.values().get("ask_price_micros")).longValue());
        assertEquals(455000L, ((Number) output.values().get("midpoint_micros")).longValue());
        assertEquals(42L, ((Number) output.values().get("last_canonical_commit_seq")).longValue());
        assertEquals(false, output.values().get("crossed"));
    }

    @Test
    void refreshCursorUsesCommitSeqAndMarketTickerWithoutDuplicateGrowth() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "market_ticker", "MKT-2",
            "last_event_ts_ms", 2000L,
            "last_canonical_event_id", "source-2",
            "last_canonical_commit_seq", 43L,
            "best_bid_micros", 450000L,
            "best_ask_micros", 480000L,
            "midpoint_micros", 465000L,
            "open_interest", null,
            "payload", "{\"midpoint_micros\":465000}",
            "updated_at", Instant.parse("2026-05-20T00:00:02Z")
        )));
        JdbcLatestMarketStateReader reader = new JdbcLatestMarketStateReader(jdbc::openConnection);

        List<FeatureOutputRow> rows = reader.readRows(FeatureOutputReadRequest.afterCreatedAt(
            List.of(BestBidOfferFeatureModule.FEATURE_NAME),
            new FeatureOutputCursor(
                Instant.ofEpochMilli(42L),
                "latest_market_state:42:MKT-1"
            ),
            10
        ));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains(
            "last_canonical_commit_seq > ? or (last_canonical_commit_seq = ? and market_ticker > ?)"
        ));
        assertTrue(sql.contains(
            "order by last_canonical_commit_seq asc nulls first, updated_at asc, market_ticker asc"
        ));
        assertEquals(List.of(42L, 42L, "MKT-1", 10), jdbc.bindings);
        assertEquals("latest_market_state:43:MKT-2", rows.get(0).featureEventId());
        assertEquals(Instant.ofEpochMilli(43L), rows.get(0).createdAt());
    }

    @Test
    void legacyUpdatedAtCursorStillWorksWhenCommitSeqCursorIsUnavailable() {
        Instant cursorTime = Instant.parse("2026-05-20T00:00:01Z");
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "market_ticker", "MKT-2",
            "last_event_ts_ms", 2000L,
            "last_canonical_event_id", "source-2",
            "last_canonical_commit_seq", null,
            "best_bid_micros", 450000L,
            "best_ask_micros", 480000L,
            "midpoint_micros", 465000L,
            "open_interest", null,
            "payload", "{\"midpoint_micros\":1}",
            "updated_at", Instant.parse("2026-05-20T00:00:02Z")
        )));
        JdbcLatestMarketStateReader reader = new JdbcLatestMarketStateReader(jdbc::openConnection);

        List<FeatureOutputRow> rows = reader.readRows(FeatureOutputReadRequest.afterCreatedAt(
            List.of(BestBidOfferFeatureModule.FEATURE_NAME),
            new FeatureOutputCursor(cursorTime, "MKT-1"),
            10
        ));

        String sql = jdbc.preparedSql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("updated_at > ? or (updated_at = ? and market_ticker > ?)"));
        assertEquals(List.of(Timestamp.from(cursorTime), Timestamp.from(cursorTime), "MKT-1", 10), jdbc.bindings);
        assertEquals("MKT-2", rows.get(0).featureEventId());
        assertEquals(Instant.parse("2026-05-20T00:00:02Z"), rows.get(0).createdAt());
        assertEquals(465000L, ((Number) rows.get(0).output().values().get("midpoint_micros")).longValue());
    }

    @Test
    void nonBboFeatureFilterShortCircuitsWithoutOpeningDatabase() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of());
        JdbcLatestMarketStateReader reader = new JdbcLatestMarketStateReader(jdbc::openConnection);

        List<FeatureOutput> outputs = reader.read(FeatureOutputReadRequest.recent(List.of("feature.trade_tape"), 10));

        assertEquals(List.of(), outputs);
        assertEquals(0, jdbc.openConnections);
    }

    @Test
    void invalidPayloadFailsWithMarketContext() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(row(
            "market_ticker", "MKT-1",
            "last_event_ts_ms", 1000L,
            "last_canonical_event_id", "source-1",
            "last_canonical_commit_seq", 42L,
            "best_bid_micros", 440000L,
            "best_ask_micros", 470000L,
            "midpoint_micros", 455000L,
            "open_interest", null,
            "payload", "{bad-json",
            "updated_at", Instant.parse("2026-05-20T00:00:01Z")
        )));
        JdbcLatestMarketStateReader reader = new JdbcLatestMarketStateReader(jdbc::openConnection);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> reader.read(FeatureOutputReadRequest.defaultRecent())
        );

        assertTrue(thrown.getMessage().contains("MKT-1"));
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            row.put((String) keyValues[index], keyValues[index + 1]);
        }
        return row;
    }

    private static final class RecordingJdbc {
        private final List<Map<String, Object>> rows;
        private final List<Object> bindings = new ArrayList<>();
        private String preparedSql;
        private int openConnections;

        private RecordingJdbc(List<Map<String, Object>> rows) {
            this.rows = rows;
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
                case "prepareStatement" -> {
                    preparedSql = (String) args[0];
                    yield preparedStatement();
                }
                case "close" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement() {
            InvocationHandler handler = this::handlePreparedStatementInvocation;
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                handler
            );
        }

        private Object handlePreparedStatementInvocation(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "setObject" -> {
                    int parameterIndex = (Integer) args[0];
                    while (bindings.size() < parameterIndex) {
                        bindings.add(null);
                    }
                    bindings.set(parameterIndex - 1, args[1]);
                    yield null;
                }
                case "executeQuery" -> resultSet();
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet() {
            InvocationHandler handler = new InvocationHandler() {
                private int index = -1;

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "next" -> ++index < rows.size();
                        case "getString" -> stringValue(rows.get(index).get((String) args[0]));
                        case "getObject" -> rows.get(index).get((String) args[0]);
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

        private static String stringValue(Object value) {
            return value == null ? null : value.toString();
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
