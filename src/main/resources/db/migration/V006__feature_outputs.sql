create table if not exists feature_outputs (
    feature_event_id text primary key,
    source_event_id text,
    feature_name text not null,
    feature_version integer not null,
    market_ticker text,
    event_ts_ms bigint,
    "values" jsonb not null,
    created_at timestamptz not null default now()
);

create index if not exists feature_outputs_feature_market_event_ts_idx
    on feature_outputs (feature_name, market_ticker, event_ts_ms);

-- Plain Postgres unique indexes allow repeated NULL keys; normalize nullable
-- identifiers so retries stay idempotent when source_event_id or market_ticker is absent.
create unique index if not exists feature_outputs_feature_source_market_uidx
    on feature_outputs (
        feature_name,
        feature_version,
        (source_event_id is null),
        coalesce(source_event_id, ''),
        (market_ticker is null),
        coalesce(market_ticker, '')
    );
