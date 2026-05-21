create table if not exists market_feature_stats (
    market_ticker text primary key,
    feature_count bigint not null default 0,
    bbo_sample_count bigint not null default 0,
    ticker_sample_count bigint not null default 0,
    trade_sample_count bigint not null default 0,
    bbo_chart_count bigint not null default 0,
    ticker_chart_count bigint not null default 0,
    trade_chart_count bigint not null default 0,
    bbo_first_chart_ts_ms bigint,
    bbo_last_chart_ts_ms bigint,
    ticker_first_chart_ts_ms bigint,
    ticker_last_chart_ts_ms bigint,
    trade_first_chart_ts_ms bigint,
    trade_last_chart_ts_ms bigint,
    first_chart_ts_ms bigint,
    last_chart_ts_ms bigint,
    latest_feature_event_ts_ms bigint,
    updated_at timestamptz not null default now(),
    constraint market_feature_stats_counts_non_negative check (
        feature_count >= 0
        and bbo_sample_count >= 0
        and ticker_sample_count >= 0
        and trade_sample_count >= 0
        and bbo_chart_count >= 0
        and ticker_chart_count >= 0
        and trade_chart_count >= 0
    )
);

create index if not exists market_feature_stats_last_chart_ts_idx
    on market_feature_stats (last_chart_ts_ms desc nulls last);

create index if not exists market_feature_stats_latest_feature_ts_idx
    on market_feature_stats (latest_feature_event_ts_ms desc nulls last);
