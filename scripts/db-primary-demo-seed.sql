begin;

create temp table db_primary_demo_markets (
    market_ticker text primary key
) on commit drop;

insert into db_primary_demo_markets (market_ticker) values
    ('DEMO-DBPRIMARY-26MAY19-T50'),
    ('DEMO-DBPRIMARY-26MAY19-T60');

delete from feature_outputs
where feature_event_id like 'demo-db-primary-%'
   or source_event_id like 'demo-db-primary-%'
   or market_ticker in (select market_ticker from db_primary_demo_markets);

delete from latest_market_state
where market_ticker in (select market_ticker from db_primary_demo_markets);

delete from canonical_events
where event_id like 'demo-db-primary-canonical-%'
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
) values
    (
        'DEMO-DBPRIMARY-26MAY19-T50',
        'DEMO-DBPRIMARY-26MAY19',
        'DEMO-DBPRIMARY',
        'open',
        now() - interval '1 day',
        now() + interval '1 day',
        now() + interval '1 day 1 hour',
        '{"demo_seed":true,"contract":"yes/no binary market"}'::jsonb,
        '{"demo_seed":true,"title":"Demo DB-primary market at 50","ticker":"DEMO-DBPRIMARY-26MAY19-T50","yes_sub_title":"Above 50","no_sub_title":"At or below 50"}'::jsonb
    ),
    (
        'DEMO-DBPRIMARY-26MAY19-T60',
        'DEMO-DBPRIMARY-26MAY19',
        'DEMO-DBPRIMARY',
        'open',
        now() - interval '1 day',
        now() + interval '1 day',
        now() + interval '1 day 1 hour',
        '{"demo_seed":true,"contract":"yes/no binary market"}'::jsonb,
        '{"demo_seed":true,"title":"Demo DB-primary market at 60","ticker":"DEMO-DBPRIMARY-26MAY19-T60","yes_sub_title":"Above 60","no_sub_title":"At or below 60"}'::jsonb
    );

with seed_clock as (
    select (floor(extract(epoch from now()) * 1000)::bigint - 11 * 60 * 1000) as base_ts_ms
),
bbo_rows (
    event_id,
    market_ticker,
    offset_ms,
    bid_price_micros,
    ask_price_micros,
    bid_quantity_micros,
    ask_quantity_micros
) as (
    values
        ('demo-db-primary-canonical-bbo-t50-001', 'DEMO-DBPRIMARY-26MAY19-T50', 0, 420000, 440000, 1100000, 1250000),
        ('demo-db-primary-canonical-bbo-t50-002', 'DEMO-DBPRIMARY-26MAY19-T50', 60000, 425000, 445000, 1130000, 1240000),
        ('demo-db-primary-canonical-bbo-t50-003', 'DEMO-DBPRIMARY-26MAY19-T50', 120000, 430000, 450000, 1170000, 1220000),
        ('demo-db-primary-canonical-bbo-t50-004', 'DEMO-DBPRIMARY-26MAY19-T50', 180000, 435000, 455000, 1190000, 1210000),
        ('demo-db-primary-canonical-bbo-t50-005', 'DEMO-DBPRIMARY-26MAY19-T50', 240000, 432000, 452000, 1210000, 1200000),
        ('demo-db-primary-canonical-bbo-t50-006', 'DEMO-DBPRIMARY-26MAY19-T50', 300000, 438000, 458000, 1220000, 1180000),
        ('demo-db-primary-canonical-bbo-t50-007', 'DEMO-DBPRIMARY-26MAY19-T50', 360000, 442000, 462000, 1240000, 1160000),
        ('demo-db-primary-canonical-bbo-t50-008', 'DEMO-DBPRIMARY-26MAY19-T50', 420000, 448000, 468000, 1260000, 1150000),
        ('demo-db-primary-canonical-bbo-t50-009', 'DEMO-DBPRIMARY-26MAY19-T50', 480000, 451000, 471000, 1270000, 1130000),
        ('demo-db-primary-canonical-bbo-t50-010', 'DEMO-DBPRIMARY-26MAY19-T50', 540000, 456000, 476000, 1280000, 1110000),
        ('demo-db-primary-canonical-bbo-t50-011', 'DEMO-DBPRIMARY-26MAY19-T50', 600000, 459000, 479000, 1300000, 1100000),
        ('demo-db-primary-canonical-bbo-t50-012', 'DEMO-DBPRIMARY-26MAY19-T50', 660000, 463000, 483000, 1310000, 1090000),
        ('demo-db-primary-canonical-bbo-t60-001', 'DEMO-DBPRIMARY-26MAY19-T60', 0, 560000, 585000, 910000, 1030000),
        ('demo-db-primary-canonical-bbo-t60-002', 'DEMO-DBPRIMARY-26MAY19-T60', 60000, 552000, 577000, 930000, 1020000),
        ('demo-db-primary-canonical-bbo-t60-003', 'DEMO-DBPRIMARY-26MAY19-T60', 120000, 548000, 573000, 950000, 1010000),
        ('demo-db-primary-canonical-bbo-t60-004', 'DEMO-DBPRIMARY-26MAY19-T60', 180000, 555000, 580000, 970000, 990000),
        ('demo-db-primary-canonical-bbo-t60-005', 'DEMO-DBPRIMARY-26MAY19-T60', 240000, 561000, 586000, 990000, 970000),
        ('demo-db-primary-canonical-bbo-t60-006', 'DEMO-DBPRIMARY-26MAY19-T60', 300000, 566000, 591000, 1010000, 960000),
        ('demo-db-primary-canonical-bbo-t60-007', 'DEMO-DBPRIMARY-26MAY19-T60', 360000, 570000, 595000, 1030000, 950000),
        ('demo-db-primary-canonical-bbo-t60-008', 'DEMO-DBPRIMARY-26MAY19-T60', 420000, 574000, 599000, 1050000, 940000),
        ('demo-db-primary-canonical-bbo-t60-009', 'DEMO-DBPRIMARY-26MAY19-T60', 480000, 579000, 604000, 1060000, 930000),
        ('demo-db-primary-canonical-bbo-t60-010', 'DEMO-DBPRIMARY-26MAY19-T60', 540000, 583000, 608000, 1070000, 920000),
        ('demo-db-primary-canonical-bbo-t60-011', 'DEMO-DBPRIMARY-26MAY19-T60', 600000, 587000, 612000, 1080000, 910000),
        ('demo-db-primary-canonical-bbo-t60-012', 'DEMO-DBPRIMARY-26MAY19-T60', 660000, 592000, 617000, 1090000, 900000)
)
insert into canonical_events (
    event_id,
    stream_name,
    event_type,
    schema_version,
    market_ticker,
    event_ts_ms,
    payload
)
select
    bbo_rows.event_id,
    'derived.top_of_book',
    'top_of_book_update',
    1,
    bbo_rows.market_ticker,
    seed_clock.base_ts_ms + bbo_rows.offset_ms,
    jsonb_build_object(
        'event_id', bbo_rows.event_id,
        'event_type', 'top_of_book_update',
        'schema_version', 1,
        'stream_name', 'derived.top_of_book',
        'metadata', jsonb_build_object(
            'source', 'demo',
            'market_ticker', bbo_rows.market_ticker,
            'event_ts_ms', seed_clock.base_ts_ms + bbo_rows.offset_ms
        ),
        'bid_price_micros', bbo_rows.bid_price_micros,
        'ask_price_micros', bbo_rows.ask_price_micros,
        'bid_quantity_micros', bbo_rows.bid_quantity_micros,
        'ask_quantity_micros', bbo_rows.ask_quantity_micros,
        'crossed', false,
        'demo_seed', true
    )
from bbo_rows
cross join seed_clock;

with seed_clock as (
    select (floor(extract(epoch from now()) * 1000)::bigint - 11 * 60 * 1000) as base_ts_ms
),
ticker_rows (event_id, market_ticker, offset_ms, price_micros, yes_bid_micros, yes_ask_micros, volume_micros) as (
    values
        ('demo-db-primary-canonical-ticker-t50-001', 'DEMO-DBPRIMARY-26MAY19-T50', 720000, 474000, 463000, 483000, 42000000),
        ('demo-db-primary-canonical-ticker-t60-001', 'DEMO-DBPRIMARY-26MAY19-T60', 720000, 604000, 592000, 617000, 36000000)
)
insert into canonical_events (
    event_id,
    stream_name,
    event_type,
    schema_version,
    market_ticker,
    event_ts_ms,
    payload
)
select
    ticker_rows.event_id,
    'canonical.ticker',
    'ticker_update',
    1,
    ticker_rows.market_ticker,
    seed_clock.base_ts_ms + ticker_rows.offset_ms,
    jsonb_build_object(
        'event_id', ticker_rows.event_id,
        'event_type', 'ticker_update',
        'schema_version', 1,
        'stream_name', 'canonical.ticker',
        'metadata', jsonb_build_object(
            'source', 'demo',
            'market_ticker', ticker_rows.market_ticker,
            'event_ts_ms', seed_clock.base_ts_ms + ticker_rows.offset_ms
        ),
        'price_micros', ticker_rows.price_micros,
        'yes_bid_micros', ticker_rows.yes_bid_micros,
        'yes_ask_micros', ticker_rows.yes_ask_micros,
        'volume_micros', ticker_rows.volume_micros,
        'demo_seed', true
    )
from ticker_rows
cross join seed_clock;

with seed_clock as (
    select (floor(extract(epoch from now()) * 1000)::bigint - 11 * 60 * 1000) as base_ts_ms
),
trade_rows (event_id, market_ticker, offset_ms, yes_price_micros, no_price_micros, quantity_micros, taker_side) as (
    values
        ('demo-db-primary-canonical-trade-t50-001', 'DEMO-DBPRIMARY-26MAY19-T50', 725000, 474000, 526000, 2500000, 'yes'),
        ('demo-db-primary-canonical-trade-t60-001', 'DEMO-DBPRIMARY-26MAY19-T60', 725000, 604000, 396000, 1800000, 'yes')
)
insert into canonical_events (
    event_id,
    stream_name,
    event_type,
    schema_version,
    market_ticker,
    event_ts_ms,
    payload
)
select
    trade_rows.event_id,
    'canonical.trade',
    'market_trade',
    1,
    trade_rows.market_ticker,
    seed_clock.base_ts_ms + trade_rows.offset_ms,
    jsonb_build_object(
        'event_id', trade_rows.event_id,
        'event_type', 'market_trade',
        'schema_version', 1,
        'stream_name', 'canonical.trade',
        'metadata', jsonb_build_object(
            'source', 'demo',
            'market_ticker', trade_rows.market_ticker,
            'event_ts_ms', seed_clock.base_ts_ms + trade_rows.offset_ms
        ),
        'trade_id', trade_rows.event_id,
        'yes_price_micros', trade_rows.yes_price_micros,
        'no_price_micros', trade_rows.no_price_micros,
        'quantity_micros', trade_rows.quantity_micros,
        'taker_side', trade_rows.taker_side,
        'demo_seed', true
    )
from trade_rows
cross join seed_clock;

commit;
