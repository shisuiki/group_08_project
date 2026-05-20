begin;

create temp table db_primary_demo_markets (
    market_ticker text primary key,
    event_ticker text not null,
    series_ticker text not null,
    title text not null,
    base_bid_micros bigint not null,
    slug text not null
) on commit drop;

insert into db_primary_demo_markets (
    market_ticker,
    event_ticker,
    series_ticker,
    title,
    base_bid_micros,
    slug
) values
    (
        'DEMO-DBPRIMARY-26MAY19-T50',
        'DEMO-DBPRIMARY-26MAY19',
        'DEMO-DBPRIMARY',
        'Demo DB-primary market at 50',
        420000,
        't50'
    ),
    (
        'DEMO-DBPRIMARY-26MAY19-T60',
        'DEMO-DBPRIMARY-26MAY19',
        'DEMO-DBPRIMARY',
        'Demo DB-primary market at 60',
        560000,
        't60'
    );

delete from feature_outputs
where feature_event_id like 'demo-db-primary-%'
   or source_event_id like 'demo-db-primary-%'
   or market_ticker in (select market_ticker from db_primary_demo_markets);

delete from latest_market_state
where market_ticker in (select market_ticker from db_primary_demo_markets);

delete from canonical_events
where event_id like 'demo-db-primary-canonical-%'
   or replay_id = 'demo-db-primary-long-replay'
   or market_ticker in (select market_ticker from db_primary_demo_markets);

delete from market_metadata
where market_ticker in (select market_ticker from db_primary_demo_markets);

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
    now() - interval '1 day',
    now() + interval '1 day',
    now() + interval '1 day 1 hour',
    '{"demo_seed":true,"scenario":"long-replay","contract":"yes/no binary market"}'::jsonb,
    jsonb_build_object(
        'demo_seed', true,
        'scenario', 'long-replay',
        'title', title,
        'ticker', market_ticker,
        'yes_sub_title', 'Above threshold',
        'no_sub_title', 'At or below threshold'
    )
from db_primary_demo_markets;

with seed_clock as (
    select (floor(extract(epoch from now()) * 1000)::bigint - 3 * 60 * 60 * 1000) as base_ts_ms
),
bbo_rows as (
    select
        format('demo-db-primary-canonical-bbo-%s-%s', m.slug, lpad((step + 1)::text, 3, '0')) as event_id,
        m.market_ticker,
        step,
        seed_clock.base_ts_ms + step * 60 * 1000 as event_ts_ms,
        m.base_bid_micros + ((step % 37) - 18) * 1000 + ((step / 15) % 6) * 1000 as bid_price_micros,
        1000000 + (step % 20) * 25000 as bid_quantity_micros,
        1100000 + ((step + 7) % 20) * 25000 as ask_quantity_micros
    from db_primary_demo_markets m
    cross join generate_series(0, 180) as steps(step)
    cross join seed_clock
)
insert into canonical_events (
    event_id,
    replay_id,
    stream_name,
    event_type,
    schema_version,
    market_ticker,
    event_ts_ms,
    payload
)
select
    event_id,
    'demo-db-primary-long-replay',
    'derived.top_of_book',
    'top_of_book_update',
    1,
    market_ticker,
    event_ts_ms,
    jsonb_build_object(
        'event_id', event_id,
        'event_type', 'top_of_book_update',
        'schema_version', 1,
        'stream_name', 'derived.top_of_book',
        'metadata', jsonb_build_object(
            'source', 'demo',
            'market_ticker', market_ticker,
            'event_ts_ms', event_ts_ms
        ),
        'bid_price_micros', bid_price_micros,
        'ask_price_micros', bid_price_micros + 20000,
        'bid_quantity_micros', bid_quantity_micros,
        'ask_quantity_micros', ask_quantity_micros,
        'crossed', false,
        'demo_seed', true,
        'scenario', 'long-replay'
    )
from bbo_rows;

with seed_clock as (
    select (floor(extract(epoch from now()) * 1000)::bigint - 3 * 60 * 60 * 1000) as base_ts_ms
),
ticker_rows as (
    select
        format('demo-db-primary-canonical-ticker-%s-001', slug) as event_id,
        market_ticker,
        base_bid_micros + 182000 as price_micros,
        base_bid_micros + 172000 as yes_bid_micros,
        base_bid_micros + 192000 as yes_ask_micros,
        42000000 as volume_micros,
        seed_clock.base_ts_ms + 181 * 60 * 1000 as event_ts_ms
    from db_primary_demo_markets
    cross join seed_clock
)
insert into canonical_events (
    event_id,
    replay_id,
    stream_name,
    event_type,
    schema_version,
    market_ticker,
    event_ts_ms,
    payload
)
select
    event_id,
    'demo-db-primary-long-replay',
    'canonical.ticker',
    'ticker_update',
    1,
    market_ticker,
    event_ts_ms,
    jsonb_build_object(
        'event_id', event_id,
        'event_type', 'ticker_update',
        'schema_version', 1,
        'stream_name', 'canonical.ticker',
        'metadata', jsonb_build_object(
            'source', 'demo',
            'market_ticker', market_ticker,
            'event_ts_ms', event_ts_ms
        ),
        'price_micros', price_micros,
        'yes_bid_micros', yes_bid_micros,
        'yes_ask_micros', yes_ask_micros,
        'volume_micros', volume_micros,
        'demo_seed', true,
        'scenario', 'long-replay'
    )
from ticker_rows;

with seed_clock as (
    select (floor(extract(epoch from now()) * 1000)::bigint - 3 * 60 * 60 * 1000) as base_ts_ms
),
trade_rows as (
    select
        format('demo-db-primary-canonical-trade-%s-001', slug) as event_id,
        market_ticker,
        base_bid_micros + 184000 as yes_price_micros,
        1000000 - (base_bid_micros + 184000) as no_price_micros,
        2500000 as quantity_micros,
        'yes' as taker_side,
        seed_clock.base_ts_ms + 181 * 60 * 1000 + 5000 as event_ts_ms
    from db_primary_demo_markets
    cross join seed_clock
)
insert into canonical_events (
    event_id,
    replay_id,
    stream_name,
    event_type,
    schema_version,
    market_ticker,
    event_ts_ms,
    payload
)
select
    event_id,
    'demo-db-primary-long-replay',
    'canonical.trade',
    'market_trade',
    1,
    market_ticker,
    event_ts_ms,
    jsonb_build_object(
        'event_id', event_id,
        'event_type', 'market_trade',
        'schema_version', 1,
        'stream_name', 'canonical.trade',
        'metadata', jsonb_build_object(
            'source', 'demo',
            'market_ticker', market_ticker,
            'event_ts_ms', event_ts_ms
        ),
        'trade_id', event_id,
        'yes_price_micros', yes_price_micros,
        'no_price_micros', no_price_micros,
        'quantity_micros', quantity_micros,
        'taker_side', taker_side,
        'demo_seed', true,
        'scenario', 'long-replay'
    )
from trade_rows;

commit;
