begin;

create temp table semantic_demo_fixture on commit drop as
with seed_rows as (
    select generate_series(1, (:fixture_rows)::integer) as i
),
classified as (
    select
        i,
        format('DEMO-SEMANTIC-%s', lpad(i::text, 3, '0')) as market_ticker,
        format('DEMO-SEMANTIC-EVENT-%s', lpad((1 + ((i - 1) / 10))::text, 2, '0')) as event_ticker,
        'DEMO-SEMANTIC' as series_ticker,
        (array[
            'weather',
            'economics',
            'politics',
            'sports',
            'energy',
            'public_health',
            'technology',
            'geopolitics'
        ])[1 + ((i - 1) % 8)] as sector,
        (array[
            'precipitation',
            'inflation',
            'elections',
            'championships',
            'power_grid',
            'epidemiology',
            'ai_compute',
            'trade_policy',
            'temperature',
            'labor_market',
            'legislation',
            'player_props'
        ])[1 + ((i - 1) % 12)] as subsector,
        (array[
            'forecast',
            'macro',
            'election',
            'match_result',
            'supply',
            'case_count',
            'earnings',
            'policy',
            'climate',
            'rates'
        ])[1 + ((i - 1) % 10)] as event_type,
        (array['us', 'global', 'europe', 'asia', 'latin_america'])[1 + ((i - 1) % 5)] as region,
        (array['intraday', 'daily', 'weekly', 'monthly'])[1 + ((i - 1) % 4)] as time_horizon,
        (array['high', 'medium', 'low'])[1 + ((i - 1) % 3)] as liquidity_bucket,
        (array['low', 'medium', 'high'])[1 + ((i + 1) % 3)] as risk_bucket,
        (0.62 + ((i % 37)::numeric / 100))::numeric(4, 2) as confidence,
        250000 + ((i * 7900) % 520000) as midpoint_micros,
        900 + i * 37 + ((i % 11) * 90) as open_interest
    from seed_rows
),
enriched as (
    select
        *,
        format('Semantic demo market %s: %s %s', lpad(i::text, 3, '0'), sector, event_type) as title,
        jsonb_build_array(sector, event_type, region, liquidity_bucket, risk_bucket, 'demo-semantic') as tags,
        1770000000000::bigint + i * 1000::bigint as event_ts_ms,
        greatest(10000, midpoint_micros - (9000 + (i % 7) * 1000))::bigint as best_bid_micros,
        least(990000, midpoint_micros + (9000 + (i % 7) * 1000))::bigint as best_ask_micros
    from classified
)
select * from enriched;

insert into market_metadata (
    market_ticker,
    event_ticker,
    series_ticker,
    status,
    open_time,
    close_time,
    settlement_time,
    rules_payload,
    market_payload
)
select
    market_ticker,
    event_ticker,
    series_ticker,
    'open',
    timestamptz '2026-05-20 00:00:00+00' + (i || ' minutes')::interval,
    timestamptz '2026-05-27 00:00:00+00' + (i || ' minutes')::interval,
    timestamptz '2026-05-27 01:00:00+00' + (i || ' minutes')::interval,
    jsonb_build_object(
        'fixture', true,
        'fixture_name', 'semantic-demo-v1',
        'contract', 'yes/no binary market',
        'sector', sector,
        'event_type', event_type
    ),
    jsonb_build_object(
        'fixture', true,
        'fixture_name', 'semantic-demo-v1',
        'ticker', market_ticker,
        'title', title,
        'yes_sub_title', format('Yes on %s', event_type),
        'no_sub_title', format('No on %s', event_type),
        'category', sector,
        'region', region
    )
from semantic_demo_fixture
on conflict (market_ticker) do update set
    event_ticker = excluded.event_ticker,
    series_ticker = excluded.series_ticker,
    status = excluded.status,
    open_time = excluded.open_time,
    close_time = excluded.close_time,
    settlement_time = excluded.settlement_time,
    rules_payload = excluded.rules_payload,
    market_payload = excluded.market_payload,
    updated_at = now();

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
)
select
    market_ticker,
    'v1',
    'fixture/semantic-demo-v1',
    'fixture-semantic-demo-v1',
    'fixture-prompt-' || lpad(i::text, 3, '0'),
    'fixture-source-sha-' || lpad(i::text, 3, '0'),
    'fixture-source-fingerprint-' || lpad(i::text, 3, '0'),
    'fixture-semantic-demo-v1:' || market_ticker,
    sector,
    subsector,
    event_type,
    region,
    time_horizon,
    liquidity_bucket,
    risk_bucket,
    tags,
    confidence,
    format('Deterministic semantic demo classification for %s / %s.', sector, event_type),
    jsonb_build_object(
        'fixture', true,
        'fixture_name', 'semantic-demo-v1',
        'model', 'fixture/semantic-demo-v1',
        'taxonomy_version', 'v1',
        'market_ticker', market_ticker,
        'sector', sector,
        'subsector', subsector,
        'event_type', event_type,
        'region', region,
        'time_horizon', time_horizon,
        'liquidity_bucket', liquidity_bucket,
        'risk_bucket', risk_bucket,
        'tags', tags,
        'confidence', confidence,
        'generated_at', '2026-05-20T00:00:00Z'
    ),
    'generated',
    null,
    timestamptz '2026-05-20 00:00:00+00' + (i || ' minutes')::interval
from semantic_demo_fixture
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
    updated_at = now();

insert into latest_market_state (
    market_ticker,
    last_event_ts_ms,
    last_canonical_event_id,
    best_bid_micros,
    best_ask_micros,
    midpoint_micros,
    open_interest,
    payload
)
select
    market_ticker,
    event_ts_ms,
    'fixture-semantic-latest-' || lpad(i::text, 3, '0'),
    best_bid_micros,
    best_ask_micros,
    midpoint_micros,
    open_interest,
    jsonb_build_object(
        'fixture', true,
        'fixture_name', 'semantic-demo-v1',
        'event_type', 'top_of_book_update',
        'stream_name', 'derived.top_of_book',
        'market_ticker', market_ticker,
        'event_ts_ms', event_ts_ms,
        'best_bid_micros', best_bid_micros,
        'best_ask_micros', best_ask_micros,
        'midpoint_micros', midpoint_micros,
        'open_interest', open_interest
    )
from semantic_demo_fixture
on conflict (market_ticker) do update set
    last_event_ts_ms = excluded.last_event_ts_ms,
    last_canonical_event_id = excluded.last_canonical_event_id,
    best_bid_micros = excluded.best_bid_micros,
    best_ask_micros = excluded.best_ask_micros,
    midpoint_micros = excluded.midpoint_micros,
    open_interest = excluded.open_interest,
    payload = excluded.payload,
    updated_at = now()
where latest_market_state.last_event_ts_ms is null
   or excluded.last_event_ts_ms >= latest_market_state.last_event_ts_ms;

commit;
