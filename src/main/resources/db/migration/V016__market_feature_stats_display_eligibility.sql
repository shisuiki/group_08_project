alter table market_feature_stats
    add column if not exists bbo_bars_24h_count bigint not null default 0,
    add column if not exists ticker_bars_24h_count bigint not null default 0,
    add column if not exists trade_bars_24h_count bigint not null default 0,
    add column if not exists history_bars_24h_count bigint not null default 0,
    add column if not exists bbo_24h_count bigint not null default 0,
    add column if not exists ticker_24h_count bigint not null default 0,
    add column if not exists trade_24h_count bigint not null default 0,
    add column if not exists quote_24h_count bigint not null default 0,
    add column if not exists display_eligible boolean not null default false;

alter table market_feature_stats
    drop constraint if exists market_feature_stats_display_counts_non_negative,
    add constraint market_feature_stats_display_counts_non_negative check (
        bbo_bars_24h_count >= 0
        and ticker_bars_24h_count >= 0
        and trade_bars_24h_count >= 0
        and history_bars_24h_count >= 0
        and bbo_24h_count >= 0
        and ticker_24h_count >= 0
        and trade_24h_count >= 0
        and quote_24h_count >= 0
    );

create index if not exists market_feature_stats_display_rank_idx
    on market_feature_stats (
        display_eligible desc,
        history_bars_24h_count desc,
        trade_24h_count desc,
        quote_24h_count desc,
        latest_feature_event_ts_ms desc nulls last,
        market_ticker asc
    );

with stats as (
    select
        fo.market_ticker,
        count(distinct (fo.event_ts_ms / 60000)) filter (
            where fo.feature_name = 'feature.bbo'
              and fo.event_ts_ms is not null
              and jsonb_exists(fo."values", 'midpoint_micros')
              and fo.event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
              and fo.event_ts_ms <= (extract(epoch from now()) * 1000)::bigint
        ) as bbo_bars_24h_count,
        count(distinct (fo.event_ts_ms / 60000)) filter (
            where fo.feature_name = 'feature.ticker_snapshot'
              and fo.event_ts_ms is not null
              and (
                  jsonb_exists(fo."values", 'price_micros')
                  or (
                      jsonb_exists(fo."values", 'yes_bid_micros')
                      and jsonb_exists(fo."values", 'yes_ask_micros')
                  )
              )
              and fo.event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
              and fo.event_ts_ms <= (extract(epoch from now()) * 1000)::bigint
        ) as ticker_bars_24h_count,
        count(distinct (fo.event_ts_ms / 60000)) filter (
            where fo.feature_name = 'feature.trade_tape'
              and fo.event_ts_ms is not null
              and (
                  jsonb_exists(fo."values", 'yes_price_micros')
                  or jsonb_exists(fo."values", 'no_price_micros')
              )
              and fo.event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
              and fo.event_ts_ms <= (extract(epoch from now()) * 1000)::bigint
        ) as trade_bars_24h_count,
        count(*) filter (
            where fo.feature_name = 'feature.bbo'
              and fo.event_ts_ms is not null
              and fo.event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
              and fo.event_ts_ms <= (extract(epoch from now()) * 1000)::bigint
        ) as bbo_24h_count,
        count(*) filter (
            where fo.feature_name = 'feature.ticker_snapshot'
              and fo.event_ts_ms is not null
              and fo.event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
              and fo.event_ts_ms <= (extract(epoch from now()) * 1000)::bigint
        ) as ticker_24h_count,
        count(*) filter (
            where fo.feature_name = 'feature.trade_tape'
              and fo.event_ts_ms is not null
              and fo.event_ts_ms >= ((extract(epoch from now()) * 1000)::bigint - 86400000)
              and fo.event_ts_ms <= (extract(epoch from now()) * 1000)::bigint
        ) as trade_24h_count
    from feature_outputs fo
    where fo.market_ticker is not null
      and fo.market_ticker <> ''
    group by fo.market_ticker
),
rankable as (
    select
        market_ticker,
        bbo_bars_24h_count,
        ticker_bars_24h_count,
        trade_bars_24h_count,
        case
            when bbo_bars_24h_count > 0 then bbo_bars_24h_count
            when ticker_bars_24h_count > 0 then ticker_bars_24h_count
            when trade_bars_24h_count > 0 then trade_bars_24h_count
            else 0
        end as history_bars_24h_count,
        bbo_24h_count,
        ticker_24h_count,
        trade_24h_count,
        (bbo_24h_count + ticker_24h_count) as quote_24h_count
    from stats
)
update market_feature_stats mfs
set
    bbo_bars_24h_count = rankable.bbo_bars_24h_count,
    ticker_bars_24h_count = rankable.ticker_bars_24h_count,
    trade_bars_24h_count = rankable.trade_bars_24h_count,
    history_bars_24h_count = rankable.history_bars_24h_count,
    bbo_24h_count = rankable.bbo_24h_count,
    ticker_24h_count = rankable.ticker_24h_count,
    trade_24h_count = rankable.trade_24h_count,
    quote_24h_count = rankable.quote_24h_count,
    display_eligible = rankable.history_bars_24h_count >= 10,
    updated_at = now()
from rankable
where mfs.market_ticker = rankable.market_ticker;
