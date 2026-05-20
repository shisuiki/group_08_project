package edu.illinois.group8.storage.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

public final class JdbcMarketSemanticMetadataStore implements MarketSemanticMetadataStore {
    static final String UPSERT_METADATA_SQL = """
        insert into market_semantic_metadata (
            market_ticker,
            taxonomy_version,
            model,
            prompt_version,
            prompt_hash,
            source_payload_sha256,
            source_fingerprint,
            idempotency_key,
            sector,
            subsector,
            event_type,
            region,
            time_horizon,
            liquidity_bucket,
            risk_bucket,
            tags,
            confidence,
            rationale,
            raw_response,
            status,
            error,
            generated_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?)
        on conflict (market_ticker, taxonomy_version) do update set
            model = excluded.model,
            prompt_version = excluded.prompt_version,
            prompt_hash = excluded.prompt_hash,
            source_payload_sha256 = excluded.source_payload_sha256,
            source_fingerprint = excluded.source_fingerprint,
            idempotency_key = excluded.idempotency_key,
            sector = excluded.sector,
            subsector = excluded.subsector,
            event_type = excluded.event_type,
            region = excluded.region,
            time_horizon = excluded.time_horizon,
            liquidity_bucket = excluded.liquidity_bucket,
            risk_bucket = excluded.risk_bucket,
            tags = excluded.tags,
            confidence = excluded.confidence,
            rationale = excluded.rationale,
            raw_response = excluded.raw_response,
            status = excluded.status,
            error = excluded.error,
            generated_at = excluded.generated_at,
            updated_at = now()
        where market_semantic_metadata.model is distinct from excluded.model
           or market_semantic_metadata.prompt_version is distinct from excluded.prompt_version
           or market_semantic_metadata.prompt_hash is distinct from excluded.prompt_hash
           or market_semantic_metadata.source_payload_sha256 is distinct from excluded.source_payload_sha256
           or market_semantic_metadata.source_fingerprint is distinct from excluded.source_fingerprint
           or market_semantic_metadata.idempotency_key is distinct from excluded.idempotency_key
           or market_semantic_metadata.sector is distinct from excluded.sector
           or market_semantic_metadata.subsector is distinct from excluded.subsector
           or market_semantic_metadata.event_type is distinct from excluded.event_type
           or market_semantic_metadata.region is distinct from excluded.region
           or market_semantic_metadata.time_horizon is distinct from excluded.time_horizon
           or market_semantic_metadata.liquidity_bucket is distinct from excluded.liquidity_bucket
           or market_semantic_metadata.risk_bucket is distinct from excluded.risk_bucket
           or market_semantic_metadata.tags is distinct from excluded.tags
           or market_semantic_metadata.confidence is distinct from excluded.confidence
           or market_semantic_metadata.rationale is distinct from excluded.rationale
           or market_semantic_metadata.raw_response is distinct from excluded.raw_response
           or market_semantic_metadata.status is distinct from excluded.status
           or market_semantic_metadata.error is distinct from excluded.error
           or market_semantic_metadata.generated_at is distinct from excluded.generated_at
        """;

    static final String UPSERT_JOB_SQL = """
        insert into market_semantic_metadata_jobs (
            job_id,
            market_ticker,
            taxonomy_version,
            prompt_hash,
            source_payload_sha256,
            source_fingerprint,
            idempotency_key,
            requested_model,
            actual_model,
            status,
            attempts,
            next_retry_at,
            error,
            usage
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        on conflict (idempotency_key) do update set
            market_ticker = excluded.market_ticker,
            taxonomy_version = excluded.taxonomy_version,
            prompt_hash = excluded.prompt_hash,
            source_payload_sha256 = excluded.source_payload_sha256,
            source_fingerprint = excluded.source_fingerprint,
            requested_model = excluded.requested_model,
            actual_model = excluded.actual_model,
            status = excluded.status,
            attempts = greatest(market_semantic_metadata_jobs.attempts, excluded.attempts),
            next_retry_at = excluded.next_retry_at,
            error = excluded.error,
            usage = excluded.usage,
            updated_at = now()
        """;

    static final String FIND_METADATA_SQL = """
        select
            market_ticker,
            taxonomy_version,
            model,
            prompt_version,
            prompt_hash,
            source_payload_sha256,
            source_fingerprint,
            idempotency_key,
            sector,
            subsector,
            event_type,
            region,
            time_horizon,
            liquidity_bucket,
            risk_bucket,
            tags::text as tags,
            confidence,
            rationale,
            raw_response::text as raw_response,
            status,
            error,
            generated_at
        from market_semantic_metadata
        where market_ticker = ?
          and taxonomy_version = ?
        """;

    private final JdbcConnectionFactory connectionFactory;

    public JdbcMarketSemanticMetadataStore(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static JdbcMarketSemanticMetadataStore fromDriverManager(String url, String user, String password) {
        return new JdbcMarketSemanticMetadataStore(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public void upsertMetadata(MarketSemanticMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_METADATA_SQL)) {
            bindMetadata(statement, metadata);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Failed to upsert market_semantic_metadata for " + metadata.marketTicker(),
                e
            );
        }
    }

    @Override
    public void upsertJob(MarketSemanticMetadataJob job) {
        Objects.requireNonNull(job, "job");
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_JOB_SQL)) {
            bindJob(statement, job);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Failed to upsert market_semantic_metadata_jobs for " + job.marketTicker(),
                e
            );
        }
    }

    @Override
    public Optional<MarketSemanticMetadata> findMetadata(String marketTicker, String taxonomyVersion) {
        String normalizedTicker = nonBlank(marketTicker, "marketTicker");
        String normalizedTaxonomy = nonBlank(taxonomyVersion, "taxonomyVersion");
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_METADATA_SQL)) {
            statement.setString(1, normalizedTicker);
            statement.setString(2, normalizedTaxonomy);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readMetadata(resultSet));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Failed to read market_semantic_metadata for " + normalizedTicker,
                e
            );
        }
    }

    static void bindMetadata(PreparedStatement statement, MarketSemanticMetadata metadata) throws SQLException {
        statement.setString(1, metadata.marketTicker());
        statement.setString(2, metadata.taxonomyVersion());
        statement.setString(3, metadata.model());
        statement.setString(4, metadata.promptVersion());
        statement.setString(5, metadata.promptHash());
        statement.setString(6, metadata.sourcePayloadSha256());
        statement.setString(7, metadata.sourceFingerprint());
        statement.setString(8, metadata.idempotencyKey());
        statement.setString(9, metadata.sector());
        statement.setString(10, metadata.subsector());
        statement.setString(11, metadata.eventType());
        statement.setString(12, metadata.region());
        statement.setString(13, metadata.timeHorizon());
        statement.setString(14, metadata.liquidityBucket());
        statement.setString(15, metadata.riskBucket());
        statement.setString(16, metadata.tags());
        setNullableBigDecimal(statement, 17, metadata.confidence());
        statement.setString(18, metadata.rationale());
        statement.setString(19, metadata.rawResponse());
        statement.setString(20, metadata.status());
        statement.setString(21, metadata.error());
        setNullableTimestamp(statement, 22, metadata.generatedAt());
    }

    static void bindJob(PreparedStatement statement, MarketSemanticMetadataJob job) throws SQLException {
        statement.setString(1, job.jobId());
        statement.setString(2, job.marketTicker());
        statement.setString(3, job.taxonomyVersion());
        statement.setString(4, job.promptHash());
        statement.setString(5, job.sourcePayloadSha256());
        statement.setString(6, job.sourceFingerprint());
        statement.setString(7, job.idempotencyKey());
        statement.setString(8, job.requestedModel());
        statement.setString(9, job.actualModel());
        statement.setString(10, job.status());
        statement.setInt(11, job.attempts());
        setNullableTimestamp(statement, 12, job.nextRetryAt());
        statement.setString(13, job.error());
        setNullableJson(statement, 14, job.usage());
    }

    private static MarketSemanticMetadata readMetadata(ResultSet resultSet) throws SQLException {
        return new MarketSemanticMetadata(
            resultSet.getString("market_ticker"),
            resultSet.getString("taxonomy_version"),
            resultSet.getString("model"),
            resultSet.getString("prompt_version"),
            resultSet.getString("prompt_hash"),
            resultSet.getString("source_payload_sha256"),
            resultSet.getString("source_fingerprint"),
            resultSet.getString("idempotency_key"),
            resultSet.getString("sector"),
            resultSet.getString("subsector"),
            resultSet.getString("event_type"),
            resultSet.getString("region"),
            resultSet.getString("time_horizon"),
            resultSet.getString("liquidity_bucket"),
            resultSet.getString("risk_bucket"),
            resultSet.getString("tags"),
            resultSet.getBigDecimal("confidence"),
            resultSet.getString("rationale"),
            resultSet.getString("raw_response"),
            resultSet.getString("status"),
            resultSet.getString("error"),
            instantOrNull(resultSet, "generated_at")
        );
    }

    private static void setNullableBigDecimal(
        PreparedStatement statement,
        int index,
        BigDecimal value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }

    private static void setNullableJson(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value)
        throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
        }
    }

    private static Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return Instant.parse(value.toString());
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value.trim();
    }
}
