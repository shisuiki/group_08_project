package edu.illinois.group8.storage.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

public final class JdbcAcceptedEventStore implements AcceptedEventStore {
    static final String RAW_INSERT_SQL = """
        insert into raw_ws_events (
            raw_event_id,
            source,
            capture_id,
            connection_id,
            connection_sequence,
            receive_ts_ns,
            receive_wall_ts,
            market_ticker,
            source_channel,
            source_sequence,
            payload_sha256,
            raw_payload,
            ingest_status
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict do nothing
        """;

    static final String CANONICAL_INSERT_SQL = """
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
        """;

    private static final String DEFAULT_INGEST_STATUS = "stored";

    private final JdbcConnectionFactory connectionFactory;

    public JdbcAcceptedEventStore(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcAcceptedEventStore fromDriverManager(String url, String user, String password) {
        Objects.requireNonNull(url, "url");
        return new JdbcAcceptedEventStore(() -> {
            if (user == null || user.isBlank()) {
                return DriverManager.getConnection(url);
            }
            return DriverManager.getConnection(url, user, password);
        });
    }

    @Override
    public void insertRawBatch(List<RawWsDbEvent> events) throws Exception {
        insertBatch(events, RAW_INSERT_SQL, JdbcAcceptedEventStore::bindRawEvent);
    }

    @Override
    public void insertCanonicalBatch(List<CanonicalDbEvent> events) throws Exception {
        insertBatch(events, CANONICAL_INSERT_SQL, JdbcAcceptedEventStore::bindCanonicalEvent);
    }

    private <T> void insertBatch(List<T> events, String sql, BatchBinder<T> binder) throws Exception {
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) {
            return;
        }

        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Exception failure = null;
            try {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (T event : events) {
                        binder.bind(statement, event);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
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
        }
    }

    private static void bindRawEvent(PreparedStatement statement, RawWsDbEvent event) throws SQLException {
        Objects.requireNonNull(event, "event");
        statement.setString(1, event.rawEventId());
        statement.setString(2, event.source());
        statement.setString(3, event.captureId());
        statement.setString(4, event.connectionId());
        statement.setLong(5, event.connectionSequence());
        statement.setLong(6, event.receiveTsNs());
        statement.setObject(
            7,
            OffsetDateTime.ofInstant(Objects.requireNonNull(event.receiveWallTs(), "receiveWallTs"), ZoneOffset.UTC)
        );
        statement.setString(8, event.marketTicker());
        statement.setString(9, event.sourceChannel());
        setNullableLong(statement, 10, event.sourceSequence());
        statement.setString(11, event.payloadSha256());
        statement.setString(12, event.rawPayload());
        statement.setString(13, ingestStatus(event.ingestStatus()));
    }

    private static void bindCanonicalEvent(PreparedStatement statement, CanonicalDbEvent event) throws SQLException {
        Objects.requireNonNull(event, "event");
        statement.setString(1, event.eventId());
        statement.setString(2, event.rawEventId());
        statement.setString(3, event.replayId());
        statement.setString(4, event.streamName());
        statement.setString(5, event.eventType());
        statement.setInt(6, event.schemaVersion());
        statement.setString(7, event.marketTicker());
        setNullableLong(statement, 8, event.eventTsMs());
        setNullableLong(statement, 9, event.ingestTsNs());
        setNullableLong(statement, 10, event.publishTsNs());
        statement.setString(11, event.payload());
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static String ingestStatus(String ingestStatus) {
        if (ingestStatus == null || ingestStatus.isBlank()) {
            return DEFAULT_INGEST_STATUS;
        }
        return ingestStatus;
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            cause.addSuppressed(rollbackException);
        }
    }

    @FunctionalInterface
    private interface BatchBinder<T> {
        void bind(PreparedStatement statement, T event) throws SQLException;
    }
}
