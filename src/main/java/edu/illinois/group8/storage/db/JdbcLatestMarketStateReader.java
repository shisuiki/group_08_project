package edu.illinois.group8.storage.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.feature.BestBidOfferFeatureModule;
import edu.illinois.group8.feature.FeatureOutput;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JdbcLatestMarketStateReader implements FeatureOutputReader {
    private static final String CURSOR_PREFIX = "latest_market_state:";
    private static final String TABLE_NAME = "latest_market_state";
    private static final TypeReference<Map<String, Object>> VALUE_MAP = new TypeReference<>() {
    };
    private static final String SELECT_COLUMNS = """
        select
            market_ticker,
            last_event_ts_ms,
            last_canonical_event_id,
            last_canonical_commit_seq,
            best_bid_micros,
            best_ask_micros,
            midpoint_micros,
            open_interest,
            payload::text as payload,
            updated_at
        from latest_market_state
        """;

    private final JdbcConnectionFactory connectionFactory;
    private final ObjectMapper mapper;

    public JdbcLatestMarketStateReader(JdbcConnectionFactory connectionFactory) {
        this(connectionFactory, new JsonCanonicalSerializer().mapper());
    }

    JdbcLatestMarketStateReader(JdbcConnectionFactory connectionFactory, ObjectMapper mapper) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public static JdbcLatestMarketStateReader fromDriverManager(String url, String user, String password) {
        return new JdbcLatestMarketStateReader(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public List<FeatureOutput> read(FeatureOutputReadRequest request) {
        return readRows(request).stream()
            .map(FeatureOutputRow::output)
            .toList();
    }

    public List<FeatureOutputRow> readRows(FeatureOutputReadRequest request) {
        FeatureOutputReadRequest normalized = request == null
            ? FeatureOutputReadRequest.defaultRecent()
            : request;
        if (!requestsBboFeature(normalized.featureNames())) {
            return List.of();
        }

        List<Object> bindings = new ArrayList<>();
        String sql = sql(normalized, bindings);
        try (
            Connection connection = connectionFactory.openConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            for (int index = 0; index < bindings.size(); index++) {
                statement.setObject(index + 1, bindings.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FeatureOutputRow> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(readRow(resultSet));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read latest market state from " + TABLE_NAME, e);
        }
    }

    static String sql(FeatureOutputReadRequest request, List<Object> bindings) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append("where 1 = 1");
        if (request.marketTicker() != null) {
            sql.append(" and market_ticker = ?");
            bindings.add(request.marketTicker());
        }
        if (request.fromEventTsMs() != null) {
            sql.append(" and last_event_ts_ms >= ?");
            bindings.add(request.fromEventTsMs());
        }
        if (request.toEventTsMs() != null) {
            sql.append(" and last_event_ts_ms <= ?");
            bindings.add(request.toEventTsMs());
        }
        if (request.after() != null) {
            LatestMarketStateCursor latestCursor = LatestMarketStateCursor.parse(request.after().featureEventId());
            if (latestCursor != null) {
                sql.append(" and (last_canonical_commit_seq > ?");
                sql.append(" or (last_canonical_commit_seq = ? and market_ticker > ?))");
                bindings.add(latestCursor.commitSeq());
                bindings.add(latestCursor.commitSeq());
                bindings.add(latestCursor.marketTicker());
            } else {
                Timestamp updatedAt = Timestamp.from(request.after().createdAt());
                sql.append(" and (updated_at > ? or (updated_at = ? and market_ticker > ?))");
                bindings.add(updatedAt);
                bindings.add(updatedAt);
                bindings.add(request.after().featureEventId());
            }
        }
        if (request.ascending()) {
            sql.append(" order by last_canonical_commit_seq asc nulls first, updated_at asc, market_ticker asc");
        } else {
            sql.append(" order by last_canonical_commit_seq desc nulls last, updated_at desc, market_ticker asc");
        }
        sql.append(" limit ?");
        bindings.add(request.maxRows());
        return sql.toString();
    }

    private static boolean requestsBboFeature(List<String> featureNames) {
        return featureNames.isEmpty() || featureNames.contains(BestBidOfferFeatureModule.FEATURE_NAME);
    }

    private FeatureOutputRow readRow(ResultSet resultSet) throws SQLException {
        String marketTicker = resultSet.getString("market_ticker");
        Instant updatedAt = instantOrNull(resultSet, "updated_at");
        Long commitSeq = longOrNull(resultSet, "last_canonical_commit_seq");
        String payload = resultSet.getString("payload");
        try {
            Map<String, Object> values = values(payload);
            putIfPresent(values, "bid_price_micros", longOrNull(resultSet, "best_bid_micros"));
            putIfPresent(values, "ask_price_micros", longOrNull(resultSet, "best_ask_micros"));
            putIfPresent(values, "midpoint_micros", longOrNull(resultSet, "midpoint_micros"));
            putIfPresent(values, "open_interest", longOrNull(resultSet, "open_interest"));
            putIfPresent(values, "last_canonical_commit_seq", commitSeq);
            return new FeatureOutputRow(
                cursorId(commitSeq, marketTicker),
                cursorInstant(commitSeq, updatedAt),
                new FeatureOutput(
                    BestBidOfferFeatureModule.FEATURE_NAME,
                    BestBidOfferFeatureModule.FEATURE_NAME,
                    marketTicker,
                    longOrNull(resultSet, "last_event_ts_ms"),
                    resultSet.getString("last_canonical_event_id"),
                    values
                )
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to map latest market state row " + marketTicker, e);
        }
    }

    private static Instant cursorInstant(Long commitSeq, Instant updatedAt) {
        if (commitSeq == null) {
            return updatedAt;
        }
        return Instant.ofEpochMilli(commitSeq);
    }

    private static String cursorId(Long commitSeq, String marketTicker) {
        if (commitSeq == null) {
            return marketTicker;
        }
        return CURSOR_PREFIX + commitSeq + ":" + marketTicker;
    }

    private Map<String, Object> values(String payload) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        if (payload != null && !payload.isBlank()) {
            values.putAll(mapper.readValue(payload, VALUE_MAP));
        }
        return values;
    }

    private static void putIfPresent(Map<String, Object> values, String key, Long value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private record LatestMarketStateCursor(long commitSeq, String marketTicker) {
        private static LatestMarketStateCursor parse(String value) {
            if (value == null || !value.startsWith(CURSOR_PREFIX)) {
                return null;
            }
            String remainder = value.substring(CURSOR_PREFIX.length());
            int separator = remainder.indexOf(':');
            if (separator <= 0 || separator == remainder.length() - 1) {
                return null;
            }
            try {
                long commitSeq = Long.parseLong(remainder.substring(0, separator));
                String marketTicker = remainder.substring(separator + 1);
                if (marketTicker.isBlank()) {
                    return null;
                }
                return new LatestMarketStateCursor(commitSeq, marketTicker);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static Long longOrNull(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        return Instant.parse(value.toString());
    }
}
