package edu.illinois.group8.storage.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class JdbcSemanticMarketMetadataReader implements SemanticMarketMetadataReader {
    private static final String TABLE_NAME = "market_semantic_metadata";
    private static final String MARKET_TITLE_EXPRESSION = """
        coalesce(
            mm.market_payload ->> 'title',
            mm.market_payload ->> 'name',
            mm.market_payload ->> 'subtitle',
            mm.market_payload ->> 'event_title'
        )
        """;
    private static final String SELECT_COLUMNS = """
        select
            smm.market_ticker,
            mm.event_ticker,
            mm.series_ticker,
            mm.status as market_status,
            coalesce(
                mm.market_payload ->> 'title',
                mm.market_payload ->> 'name',
                mm.market_payload ->> 'subtitle',
                mm.market_payload ->> 'event_title'
            ) as market_title,
            smm.taxonomy_version,
            smm.model,
            smm.prompt_version,
            smm.status as semantic_status,
            smm.sector,
            smm.subsector,
            smm.event_type,
            smm.region,
            smm.time_horizon,
            smm.liquidity_bucket,
            smm.risk_bucket,
            smm.tags::text as tags,
            smm.confidence,
            smm.rationale,
            smm.generated_at,
            smm.updated_at,
            case when smm.generated_at is null
                then null
                else greatest(0, (extract(epoch from (now() - smm.generated_at)) * 1000)::bigint)
            end as generated_age_ms,
            greatest(0, (extract(epoch from (now() - smm.updated_at)) * 1000)::bigint) as updated_age_ms,
            lms.last_event_ts_ms,
            lms.last_canonical_event_id,
            lms.last_canonical_commit_seq,
            lms.best_bid_micros,
            lms.best_ask_micros,
            lms.midpoint_micros,
            lms.open_interest,
            lms.updated_at as latest_state_updated_at,
            case when lms.updated_at is null
                then null
                else greatest(0, (extract(epoch from (now() - lms.updated_at)) * 1000)::bigint)
            end as latest_state_age_ms
        from market_semantic_metadata smm
        left join market_metadata mm
            on mm.market_ticker = smm.market_ticker
        left join latest_market_state lms
            on lms.market_ticker = smm.market_ticker
        """;

    private final JdbcConnectionFactory connectionFactory;
    private final ObjectMapper mapper;

    public JdbcSemanticMarketMetadataReader(JdbcConnectionFactory connectionFactory) {
        this(connectionFactory, new JsonCanonicalSerializer().mapper());
    }

    JdbcSemanticMarketMetadataReader(JdbcConnectionFactory connectionFactory, ObjectMapper mapper) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public static JdbcSemanticMarketMetadataReader fromDriverManager(String url, String user, String password) {
        return new JdbcSemanticMarketMetadataReader(JdbcConnectionFactories.fromDriverManager(url, user, password));
    }

    @Override
    public List<SemanticMarketMetadataRow> read(SemanticMarketMetadataReadRequest request) {
        SemanticMarketMetadataReadRequest normalized = request == null
            ? SemanticMarketMetadataReadRequest.defaultForTaxonomy("v1")
            : request;
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
                List<SemanticMarketMetadataRow> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(readRow(resultSet));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read semantic metadata from " + TABLE_NAME, e);
        }
    }

    static String sql(SemanticMarketMetadataReadRequest request, List<Object> bindings) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append("where 1 = 1");
        sql.append(" and smm.taxonomy_version = ?");
        bindings.add(request.taxonomyVersion());
        if (request.marketTicker() != null) {
            sql.append(" and smm.market_ticker = ?");
            bindings.add(request.marketTicker());
        }
        if (request.semanticStatus() != null) {
            sql.append(" and smm.status = ?");
            bindings.add(request.semanticStatus());
        }
        if (request.marketStatus() != null) {
            sql.append(" and mm.status = ?");
            bindings.add(request.marketStatus());
        }
        if (request.tag() != null) {
            sql.append(" and exists (");
            sql.append("select 1 from jsonb_array_elements_text(smm.tags) tag(value) where tag.value = ?");
            sql.append(")");
            bindings.add(request.tag());
        }
        if (request.query() != null) {
            String pattern = "%" + request.query().toLowerCase(Locale.ROOT) + "%";
            sql.append(" and (lower(smm.market_ticker) like ?");
            sql.append(" or lower(coalesce(mm.event_ticker, '')) like ?");
            sql.append(" or lower(coalesce(mm.series_ticker, '')) like ?");
            sql.append(" or lower(coalesce(");
            sql.append(MARKET_TITLE_EXPRESSION);
            sql.append(", '')) like ?)");
            bindings.add(pattern);
            bindings.add(pattern);
            bindings.add(pattern);
            bindings.add(pattern);
        }
        sql.append(" order by lms.last_canonical_commit_seq desc nulls last, smm.updated_at desc, smm.market_ticker asc");
        sql.append(" limit ?");
        bindings.add(request.maxRows());
        return sql.toString();
    }

    private SemanticMarketMetadataRow readRow(ResultSet resultSet) throws SQLException {
        return new SemanticMarketMetadataRow(
            resultSet.getString("market_ticker"),
            resultSet.getString("event_ticker"),
            resultSet.getString("series_ticker"),
            resultSet.getString("market_status"),
            resultSet.getString("market_title"),
            resultSet.getString("taxonomy_version"),
            resultSet.getString("model"),
            resultSet.getString("prompt_version"),
            resultSet.getString("semantic_status"),
            resultSet.getString("sector"),
            resultSet.getString("subsector"),
            resultSet.getString("event_type"),
            resultSet.getString("region"),
            resultSet.getString("time_horizon"),
            resultSet.getString("liquidity_bucket"),
            resultSet.getString("risk_bucket"),
            tags(resultSet.getString("tags")),
            resultSet.getBigDecimal("confidence"),
            resultSet.getString("rationale"),
            instantOrNull(resultSet, "generated_at"),
            instantOrNull(resultSet, "updated_at"),
            longOrNull(resultSet, "generated_age_ms"),
            longOrNull(resultSet, "updated_age_ms"),
            longOrNull(resultSet, "last_event_ts_ms"),
            resultSet.getString("last_canonical_event_id"),
            longOrNull(resultSet, "last_canonical_commit_seq"),
            longOrNull(resultSet, "best_bid_micros"),
            longOrNull(resultSet, "best_ask_micros"),
            longOrNull(resultSet, "midpoint_micros"),
            longOrNull(resultSet, "open_interest"),
            instantOrNull(resultSet, "latest_state_updated_at"),
            longOrNull(resultSet, "latest_state_age_ms")
        );
    }

    private List<String> tags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (!root.isArray()) {
                return List.of(raw);
            }
            List<String> tags = new ArrayList<>();
            for (JsonNode tag : root) {
                if (tag.isTextual() && !tag.asText().isBlank()) {
                    tags.add(tag.asText());
                }
            }
            return List.copyOf(tags);
        } catch (Exception e) {
            return List.of(raw);
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
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return Instant.parse(value.toString());
    }
}
