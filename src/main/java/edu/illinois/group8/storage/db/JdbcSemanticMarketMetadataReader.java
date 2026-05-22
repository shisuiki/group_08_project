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
    private static final long DAY_MS = 86_400_000L;
    private static final String MARKET_TITLE_EXPRESSION = """
        coalesce(
            mm.market_payload ->> 'title',
            mm.market_payload ->> 'name',
            mm.market_payload ->> 'subtitle',
            mm.market_payload ->> 'event_title'
        )
        """;
    private static final String SELECT_COLUMNS = """
        with filtered as (
        select
            smm.*,
            mm.event_ticker,
            mm.series_ticker,
            mm.status as market_status,
            coalesce(
                mm.market_payload ->> 'title',
                mm.market_payload ->> 'name',
                mm.market_payload ->> 'subtitle',
                mm.market_payload ->> 'event_title'
            ) as market_title,
            lms.last_event_ts_ms,
            lms.last_canonical_event_id,
            lms.last_canonical_commit_seq,
            lms.best_bid_micros,
            lms.best_ask_micros,
            lms.midpoint_micros,
            coalesce(
                nullif(lms.open_interest, 0),
                case
                    when (mm.market_payload ->> 'open_interest_fp') ~ '^[0-9]+(\\.[0-9]+)?$'
                        then round((mm.market_payload ->> 'open_interest_fp')::numeric)::bigint
                    else null
                end
            ) as open_interest,
            coalesce(
                case
                    when (mm.market_payload ->> 'yes_bid_dollars') ~ '^[0-9]+(\\.[0-9]+)?$'
                      and (mm.market_payload ->> 'yes_ask_dollars') ~ '^[0-9]+(\\.[0-9]+)?$'
                        then round((((mm.market_payload ->> 'yes_bid_dollars')::numeric
                            + (mm.market_payload ->> 'yes_ask_dollars')::numeric) / 2) * 1000000)::bigint
                    else null
                end,
                case
                    when (mm.market_payload ->> 'last_price_dollars') ~ '^[0-9]+(\\.[0-9]+)?$'
                        then round((mm.market_payload ->> 'last_price_dollars')::numeric * 1000000)::bigint
                    else null
                end
            ) as catalog_current_midpoint_micros,
            coalesce(
                case
                    when (mm.market_payload ->> 'previous_price_dollars') ~ '^[0-9]+(\\.[0-9]+)?$'
                        then round((mm.market_payload ->> 'previous_price_dollars')::numeric * 1000000)::bigint
                    else null
                end,
                case
                    when (mm.market_payload ->> 'previous_yes_bid_dollars') ~ '^[0-9]+(\\.[0-9]+)?$'
                      and (mm.market_payload ->> 'previous_yes_ask_dollars') ~ '^[0-9]+(\\.[0-9]+)?$'
                        then round((((mm.market_payload ->> 'previous_yes_bid_dollars')::numeric
                            + (mm.market_payload ->> 'previous_yes_ask_dollars')::numeric) / 2) * 1000000)::bigint
                    else null
                end
            ) as catalog_reference_midpoint_micros,
            lms.updated_at as latest_state_updated_at,
            case when lms.updated_at is null
                then null
                else greatest(0, (extract(epoch from (now() - lms.updated_at)) * 1000)::bigint)
            end as latest_state_age_ms,
            regexp_replace(smm.market_ticker, '-[^-]+$', '') as base_market_key,
            case
                when position('-' in smm.market_ticker) > 0 then regexp_replace(smm.market_ticker, '^.*-', '')
                else null
            end as side_tag,
            bbo24h.midpoint_24h_ago_micros
        from market_semantic_metadata smm
        left join market_metadata mm
            on mm.market_ticker = smm.market_ticker
        left join latest_market_state lms
            on lms.market_ticker = smm.market_ticker
        left join market_feature_stats mfs
            on mfs.market_ticker = smm.market_ticker
        left join lateral (
            select
                case
                    when (fo."values" ->> 'midpoint_micros') ~ '^-?[0-9]+$'
                        then (fo."values" ->> 'midpoint_micros')::bigint
                    else null
                end as midpoint_24h_ago_micros
            from feature_outputs fo
            where fo.market_ticker = smm.market_ticker
              and fo.feature_name = 'feature.bbo'
              and fo.event_ts_ms is not null
              and lms.last_event_ts_ms is not null
              and fo.event_ts_ms >= lms.last_event_ts_ms - %d
              and fo.event_ts_ms <= lms.last_event_ts_ms
              and jsonb_exists(fo."values", 'midpoint_micros')
            order by fo.event_ts_ms asc
            limit 1
        ) bbo24h on true
        """.formatted(DAY_MS);
    private static final String SELECT_OUTPUT_COLUMNS = """
        )
        select
            market_ticker,
            base_market_key,
            side_tag,
            event_ticker,
            series_ticker,
            market_status,
            market_title,
            taxonomy_version,
            model,
            prompt_version,
            status as semantic_status,
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
            generated_at,
            updated_at,
            case when generated_at is null
                then null
                else greatest(0, (extract(epoch from (now() - generated_at)) * 1000)::bigint)
            end as generated_age_ms,
            greatest(0, (extract(epoch from (now() - updated_at)) * 1000)::bigint) as updated_age_ms,
            last_event_ts_ms,
            last_canonical_event_id,
            last_canonical_commit_seq,
            best_bid_micros,
            best_ask_micros,
            midpoint_micros,
            open_interest,
            sum(greatest(coalesce(open_interest, 0), 0)) over (partition by base_market_key) as aggregate_open_interest,
            coalesce(midpoint_micros, catalog_current_midpoint_micros) as current_midpoint_micros,
            coalesce(midpoint_24h_ago_micros, catalog_reference_midpoint_micros) as midpoint_24h_ago_micros,
            case when coalesce(midpoint_micros, catalog_current_midpoint_micros) is null
                  or coalesce(midpoint_24h_ago_micros, catalog_reference_midpoint_micros) is null
                then null
                else coalesce(midpoint_micros, catalog_current_midpoint_micros)
                    - coalesce(midpoint_24h_ago_micros, catalog_reference_midpoint_micros)
            end as price_change_24h_micros,
            latest_state_updated_at,
            latest_state_age_ms
        from filtered
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
        sql.append(" and coalesce(mfs.display_eligible, false)");
        sql.append(SELECT_OUTPUT_COLUMNS);
        sql.append("""
             order by
                aggregate_open_interest desc nulls last,
                last_event_ts_ms desc nulls last,
                last_canonical_commit_seq desc nulls last,
                updated_at desc,
                market_ticker asc
            """);
        sql.append(" limit ?");
        bindings.add(request.maxRows());
        return sql.toString();
    }

    private SemanticMarketMetadataRow readRow(ResultSet resultSet) throws SQLException {
        return new SemanticMarketMetadataRow(
            resultSet.getString("market_ticker"),
            resultSet.getString("base_market_key"),
            resultSet.getString("side_tag"),
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
            longOrNull(resultSet, "aggregate_open_interest"),
            longOrNull(resultSet, "current_midpoint_micros"),
            longOrNull(resultSet, "midpoint_24h_ago_micros"),
            longOrNull(resultSet, "price_change_24h_micros"),
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
