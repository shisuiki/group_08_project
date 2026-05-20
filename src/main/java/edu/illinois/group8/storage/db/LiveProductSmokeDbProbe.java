package edu.illinois.group8.storage.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;

public final class LiveProductSmokeDbProbe {
    static final String CURSOR_COMMIT_SEQ_SQL = """
        select coalesce((
            select last_commit_seq
            from featureplant_cursors
            where cursor_name = ?
        ), 0)
        """;
    static final String MAX_CANONICAL_COMMIT_SEQ_SQL = """
        select coalesce(max(canonical_commit_seq), 0)
        from canonical_events
        """;
    static final String FEATURE_OUTPUTS_FOR_PREFIX_SQL = """
        select count(*)
        from feature_outputs
        where source_event_id like ?
        """;
    static final String RECENT_NON_SMOKE_CANONICAL_EVENTS_SQL = """
        select count(*)
        from canonical_events
        where event_id not like 'live-product-smoke-%'
          and created_at >= now() - interval '15 minutes'
        """;
    static final String LATEST_NON_SMOKE_CANONICAL_AFTER_SQL = """
        select
            event_id,
            coalesce(market_ticker, ''),
            stream_name,
            canonical_commit_seq,
            coalesce(event_ts_ms, 0)
        from canonical_events
        where canonical_commit_seq > ?
          and event_id not like 'live-product-smoke-%'
          and stream_name in ('derived.top_of_book', 'canonical.ticker', 'canonical.trade')
        order by canonical_commit_seq desc
        limit 1
        """;
    static final String FEATURE_OUTPUTS_FOR_SOURCE_EVENT_SQL = """
        select
            count(*) over (),
            feature_name,
            coalesce(market_ticker, ''),
            coalesce(event_ts_ms, 0),
            coalesce(source_event_id, '')
        from feature_outputs
        where source_event_id = ?
        order by created_at desc, feature_event_id desc
        limit 1
        """;
    static final String LATEST_NON_SMOKE_FEATURE_OUTPUT_AFTER_SQL = """
        select
            coalesce(fo.source_event_id, ''),
            fo.feature_name,
            coalesce(fo.market_ticker, ''),
            coalesce(fo.event_ts_ms, 0),
            ce.stream_name,
            ce.canonical_commit_seq
        from feature_outputs fo
        join canonical_events ce on ce.event_id = fo.source_event_id
        where ce.canonical_commit_seq > ?
          and ce.event_id not like 'live-product-smoke-%'
          and fo.source_event_id not like 'live-product-smoke-%'
        order by ce.canonical_commit_seq desc, fo.created_at desc, fo.feature_event_id desc
        limit 1
        """;
    static final String RAW_PROGRESS_SQL = """
        select
            count(*),
            coalesce(max(receive_ts_ns), 0),
            coalesce((extract(epoch from (now() - max(created_at))) * 1000)::bigint, -1)
        from (
            select receive_ts_ns, created_at
            from raw_ws_events
            where created_at >= now() - (? * interval '1 second')
            order by created_at desc
            limit ?
        ) recent_raw
        """;
    static final String CANONICAL_PROGRESS_SQL = """
        select
            count(*),
            coalesce(max(canonical_commit_seq), 0),
            coalesce((extract(epoch from (now() - max(created_at))) * 1000)::bigint, -1)
        from (
            select canonical_commit_seq, created_at
            from canonical_events
            where created_at >= now() - (? * interval '1 second')
            order by canonical_commit_seq desc
            limit ?
        ) recent_canonical
        """;
    static final String FEATURE_PROGRESS_SQL = """
        select
            count(*),
            coalesce(max(event_ts_ms), 0),
            coalesce((extract(epoch from (now() - max(created_at))) * 1000)::bigint, -1)
        from (
            select event_ts_ms, created_at
            from feature_outputs
            where created_at >= now() - (? * interval '1 second')
            order by created_at desc, feature_event_id desc
            limit ?
        ) recent_feature
        """;
    static final String RAW_WITHOUT_CANONICAL_SQL = """
        select count(*)
        from (
            select raw_event_id
            from raw_ws_events
            where created_at >= now() - (? * interval '1 second')
            order by created_at desc
            limit ?
        ) raw_recent
        left join canonical_events canonical on canonical.raw_event_id = raw_recent.raw_event_id
        where canonical.raw_event_id is null
        """;
    static final String UPSERT_MARKET_METADATA_SQL = """
        insert into market_metadata (
            market_ticker,
            event_ticker,
            series_ticker,
            status,
            market_payload
        ) values (?, ?, ?, ?, ?::jsonb)
        on conflict (market_ticker) do update set
            updated_at = now(),
            market_payload = excluded.market_payload
        """;
    static final String INSERT_CANONICAL_EVENT_SQL = """
        insert into canonical_events (
            event_id,
            raw_event_id,
            replay_id,
            stream_name,
            event_type,
            schema_version,
            market_ticker,
            event_ts_ms,
            ingest_ts_ns,
            publish_ts_ns,
            payload
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        on conflict do nothing
        returning canonical_commit_seq
        """;

    private static final String SMOKE_SOURCE = "live_product_smoke";
    private static final String EVENT_TICKER = "LIVE-PRODUCT-SMOKE";
    private static final String SERIES_TICKER = "LIVE-PRODUCT-SMOKE";

    private final JdbcConnectionFactory connectionFactory;
    private final ObjectMapper mapper;
    private final LongSupplier eventClockMs;

    public LiveProductSmokeDbProbe(JdbcConnectionFactory connectionFactory) {
        this(connectionFactory, new JsonCanonicalSerializer().mapper(), System::currentTimeMillis);
    }

    LiveProductSmokeDbProbe(JdbcConnectionFactory connectionFactory, ObjectMapper mapper, LongSupplier eventClockMs) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.eventClockMs = Objects.requireNonNull(eventClockMs, "eventClockMs");
    }

    public long cursorCommitSeq(String cursorName) {
        String normalizedCursorName = normalize(cursorName, "cursorName");
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(CURSOR_COMMIT_SEQ_SQL)) {
            statement.setString(1, normalizedCursorName);
            return singleLong(statement, "featureplant cursor");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read featureplant cursor", e);
        }
    }

    public long maxCanonicalCommitSeq() {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(MAX_CANONICAL_COMMIT_SEQ_SQL)) {
            return singleLong(statement, "max canonical commit sequence");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read max canonical commit sequence", e);
        }
    }

    public SeedResult seedCanonicalEvents(String runId, String marketTicker, String prefix) {
        String normalizedRunId = normalize(runId, "runId");
        String normalizedMarketTicker = normalize(marketTicker, "marketTicker");
        String normalizedPrefix = normalize(prefix, "prefix");
        long baseTsMs = eventClockMs.getAsLong();
        List<SeedEvent> events = List.of(
            bboEvent(normalizedRunId, normalizedMarketTicker, normalizedPrefix, baseTsMs + 1),
            tickerEvent(normalizedRunId, normalizedMarketTicker, normalizedPrefix, baseTsMs + 2),
            tradeEvent(normalizedRunId, normalizedMarketTicker, normalizedPrefix, baseTsMs + 3)
        );

        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Exception failure = null;
            try {
                upsertMarketMetadata(connection, normalizedRunId, normalizedMarketTicker);
                SeedResult result = insertCanonicalEvents(connection, events);
                connection.commit();
                return result;
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
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to seed live-product smoke canonical events", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to seed live-product smoke canonical events", e);
        }
    }

    public FeatureOutputProgress featureOutputsForPrefix(String prefix, String cursorName) {
        String normalizedPrefix = normalize(prefix, "prefix");
        long outputCount;
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(FEATURE_OUTPUTS_FOR_PREFIX_SQL)) {
            statement.setString(1, normalizedPrefix + "-%");
            outputCount = singleLong(statement, "feature output count");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count feature outputs", e);
        }
        return new FeatureOutputProgress(outputCount, cursorCommitSeq(cursorName));
    }

    public long recentNonSmokeCanonicalEvents() {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(RECENT_NON_SMOKE_CANONICAL_EVENTS_SQL)) {
            return singleLong(statement, "recent non-smoke canonical event count");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count recent non-smoke canonical events", e);
        }
    }

    public CanonicalEventSummary latestNonSmokeCanonicalAfter(long afterCommitSeq) {
        validateNonNegative(afterCommitSeq, "afterCommitSeq");
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(LATEST_NON_SMOKE_CANONICAL_AFTER_SQL)) {
            statement.setLong(1, afterCommitSeq);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new CanonicalEventSummary(
                    resultSet.getString(1),
                    resultSet.getString(2),
                    resultSet.getString(3),
                    resultSet.getLong(4),
                    resultSet.getLong(5)
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read latest non-smoke canonical event", e);
        }
    }

    public FeatureOutputSummary featureOutputsForSourceEvent(String sourceEventId) {
        String normalizedSourceEventId = normalize(sourceEventId, "sourceEventId");
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(FEATURE_OUTPUTS_FOR_SOURCE_EVENT_SQL)) {
            statement.setString(1, normalizedSourceEventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new FeatureOutputSummary(0L, "", "", 0L, normalizedSourceEventId);
                }
                return new FeatureOutputSummary(
                    resultSet.getLong(1),
                    resultSet.getString(2),
                    resultSet.getString(3),
                    resultSet.getLong(4),
                    resultSet.getString(5)
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read feature outputs for source event", e);
        }
    }

    public LiveFeatureOutputSummary latestNonSmokeFeatureOutputAfter(long afterCommitSeq) {
        validateNonNegative(afterCommitSeq, "afterCommitSeq");
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(LATEST_NON_SMOKE_FEATURE_OUTPUT_AFTER_SQL)) {
            statement.setLong(1, afterCommitSeq);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new LiveFeatureOutputSummary(
                    resultSet.getString(1),
                    resultSet.getString(2),
                    resultSet.getString(3),
                    resultSet.getLong(4),
                    resultSet.getString(5),
                    resultSet.getLong(6)
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read latest non-smoke feature output", e);
        }
    }

    public PipelineReliabilitySnapshot pipelineReliabilitySnapshot(
        String cursorName,
        long windowSeconds,
        int rowLimit
    ) {
        String normalizedCursorName = normalize(cursorName, "cursorName");
        validatePositive(windowSeconds, "windowSeconds");
        if (rowLimit < 1) {
            throw new IllegalArgumentException("rowLimit must be positive");
        }

        ProgressSummary raw = progressSummary(RAW_PROGRESS_SQL, windowSeconds, rowLimit, "raw progress");
        ProgressSummary canonical = progressSummary(
            CANONICAL_PROGRESS_SQL,
            windowSeconds,
            rowLimit,
            "canonical progress"
        );
        ProgressSummary feature = progressSummary(FEATURE_PROGRESS_SQL, windowSeconds, rowLimit, "feature progress");
        long rawWithoutCanonical = boundedCount(
            RAW_WITHOUT_CANONICAL_SQL,
            windowSeconds,
            rowLimit,
            "raw without canonical count"
        );
        long cursorCommitSeq = cursorCommitSeq(normalizedCursorName);
        long cursorLagEvents = Math.max(0L, canonical.maxSequenceOrEventTs() - cursorCommitSeq);
        String status = pipelineStatus(raw, canonical, feature, cursorLagEvents);
        return new PipelineReliabilitySnapshot(
            status,
            windowSeconds,
            rowLimit,
            raw.count(),
            raw.maxSequenceOrEventTs(),
            raw.latestAgeMs(),
            canonical.count(),
            canonical.maxSequenceOrEventTs(),
            canonical.latestAgeMs(),
            cursorCommitSeq,
            cursorLagEvents,
            feature.count(),
            feature.maxSequenceOrEventTs(),
            feature.latestAgeMs(),
            rawWithoutCanonical
        );
    }

    private void upsertMarketMetadata(Connection connection, String runId, String marketTicker) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_MARKET_METADATA_SQL)) {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("market_ticker", marketTicker);
            payload.put("event_ticker", EVENT_TICKER);
            payload.put("series_ticker", SERIES_TICKER);
            payload.put("status", "open");
            payload.put("smoke_run_id", runId);
            statement.setString(1, marketTicker);
            statement.setString(2, EVENT_TICKER);
            statement.setString(3, SERIES_TICKER);
            statement.setString(4, "open");
            statement.setString(5, toJson(payload));
            statement.executeUpdate();
        }
    }

    private SeedResult insertCanonicalEvents(Connection connection, List<SeedEvent> events) throws SQLException {
        long inserted = 0;
        long maxCommitSeq = 0;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_CANONICAL_EVENT_SQL)) {
            for (SeedEvent event : events) {
                statement.setString(1, event.eventId());
                statement.setNull(2, Types.VARCHAR);
                statement.setNull(3, Types.VARCHAR);
                statement.setString(4, event.streamName());
                statement.setString(5, event.eventType());
                statement.setInt(6, 1);
                statement.setString(7, event.marketTicker());
                statement.setLong(8, event.eventTsMs());
                statement.setNull(9, Types.BIGINT);
                statement.setNull(10, Types.BIGINT);
                statement.setString(11, event.payload());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        inserted++;
                        maxCommitSeq = Math.max(maxCommitSeq, resultSet.getLong(1));
                    }
                }
            }
        }
        return new SeedResult(inserted, maxCommitSeq);
    }

    private SeedEvent bboEvent(String runId, String marketTicker, String prefix, long eventTsMs) {
        ObjectNode payload = basePayload(prefix + "-bbo-001", "top_of_book_update", "derived.top_of_book",
            marketTicker, eventTsMs, runId);
        payload.put("bid_price_micros", 451_000);
        payload.put("ask_price_micros", 472_000);
        payload.put("bid_quantity_micros", 1_200_000);
        payload.put("ask_quantity_micros", 1_000_000);
        payload.put("crossed", false);
        return new SeedEvent(
            prefix + "-bbo-001",
            "derived.top_of_book",
            "top_of_book_update",
            marketTicker,
            eventTsMs,
            toJson(payload)
        );
    }

    private SeedEvent tickerEvent(String runId, String marketTicker, String prefix, long eventTsMs) {
        ObjectNode payload = basePayload(prefix + "-ticker-001", "ticker_update", "canonical.ticker",
            marketTicker, eventTsMs, runId);
        payload.put("price_micros", 462_000);
        payload.put("yes_bid_micros", 451_000);
        payload.put("yes_ask_micros", 472_000);
        payload.put("volume_micros", 31_000_000);
        return new SeedEvent(
            prefix + "-ticker-001",
            "canonical.ticker",
            "ticker_update",
            marketTicker,
            eventTsMs,
            toJson(payload)
        );
    }

    private SeedEvent tradeEvent(String runId, String marketTicker, String prefix, long eventTsMs) {
        ObjectNode payload = basePayload(prefix + "-trade-001", "market_trade", "canonical.trade",
            marketTicker, eventTsMs, runId);
        payload.put("trade_id", prefix + "-trade-001");
        payload.put("yes_price_micros", 462_000);
        payload.put("no_price_micros", 538_000);
        payload.put("quantity_micros", 2_100_000);
        payload.put("taker_side", "yes");
        return new SeedEvent(
            prefix + "-trade-001",
            "canonical.trade",
            "market_trade",
            marketTicker,
            eventTsMs,
            toJson(payload)
        );
    }

    private ObjectNode basePayload(
        String eventId,
        String eventType,
        String streamName,
        String marketTicker,
        long eventTsMs,
        String runId
    ) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("event_id", eventId);
        payload.put("event_type", eventType);
        payload.put("schema_version", 1);
        payload.put("stream_name", streamName);
        ObjectNode metadata = payload.putObject("metadata");
        metadata.put("source", SMOKE_SOURCE);
        metadata.put("market_ticker", marketTicker);
        metadata.put("event_ts_ms", eventTsMs);
        payload.put("smoke_run_id", runId);
        return payload;
    }

    private String toJson(ObjectNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize smoke payload", e);
        }
    }

    private static long singleLong(PreparedStatement statement, String label) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IllegalStateException(label + " query returned no rows");
            }
            return resultSet.getLong(1);
        }
    }

    private ProgressSummary progressSummary(String sql, long windowSeconds, int rowLimit, String label) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, windowSeconds);
            statement.setInt(2, rowLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(label + " query returned no rows");
                }
                return new ProgressSummary(
                    resultSet.getLong(1),
                    resultSet.getLong(2),
                    resultSet.getLong(3)
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read " + label, e);
        }
    }

    private long boundedCount(String sql, long windowSeconds, int rowLimit, String label) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, windowSeconds);
            statement.setInt(2, rowLimit);
            return singleLong(statement, label);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read " + label, e);
        }
    }

    private static String pipelineStatus(
        ProgressSummary raw,
        ProgressSummary canonical,
        ProgressSummary feature,
        long cursorLagEvents
    ) {
        if (raw.count() == 0L && canonical.count() == 0L && feature.count() == 0L) {
            return "stale";
        }
        if (raw.count() > 0L && canonical.count() == 0L) {
            return "degraded";
        }
        if (canonical.count() > 0L && feature.count() == 0L) {
            return "degraded";
        }
        if (cursorLagEvents > 0L) {
            return "degraded";
        }
        return "ok";
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            cause.addSuppressed(rollbackException);
        }
    }

    private static String normalize(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        return value.trim();
    }

    private static void validateNonNegative(long value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
    }

    private static void validatePositive(long value, String label) {
        if (value < 1) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    public record SeedResult(long seededCount, long targetCommitSeq) {
    }

    public record FeatureOutputProgress(long featureOutputCount, long cursorCommitSeq) {
    }

    public record CanonicalEventSummary(
        String eventId,
        String marketTicker,
        String streamName,
        long commitSeq,
        long eventTsMs
    ) {
    }

    public record FeatureOutputSummary(
        long count,
        String featureName,
        String marketTicker,
        long eventTsMs,
        String sourceEventId
    ) {
    }

    public record LiveFeatureOutputSummary(
        String sourceEventId,
        String featureName,
        String marketTicker,
        long eventTsMs,
        String streamName,
        long commitSeq
    ) {
    }

    public record PipelineReliabilitySnapshot(
        String status,
        long windowSeconds,
        int rowLimit,
        long rawRecentCount,
        long rawLatestReceiveTsNs,
        long rawLatestAgeMs,
        long canonicalRecentCount,
        long canonicalMaxCommitSeq,
        long canonicalLatestAgeMs,
        long cursorCommitSeq,
        long cursorLagEvents,
        long featureRecentCount,
        long featureLatestEventTsMs,
        long featureLatestAgeMs,
        long rawWithoutCanonicalCount
    ) {
    }

    private record ProgressSummary(long count, long maxSequenceOrEventTs, long latestAgeMs) {
    }

    private record SeedEvent(
        String eventId,
        String streamName,
        String eventType,
        String marketTicker,
        long eventTsMs,
        String payload
    ) {
    }

    @FunctionalInterface
    interface LongSupplier {
        long getAsLong();
    }
}
