create table if not exists market_metadata (
    market_ticker text primary key,
    event_ticker text,
    series_ticker text,
    status text,
    open_time timestamptz,
    close_time timestamptz,
    settlement_time timestamptz,
    rules_payload jsonb,
    market_payload jsonb not null,
    updated_at timestamptz not null default now()
);

create index if not exists market_metadata_event_ticker_idx
    on market_metadata (event_ticker);

create index if not exists market_metadata_series_status_idx
    on market_metadata (series_ticker, status);

create index if not exists market_metadata_status_close_time_idx
    on market_metadata (status, close_time);

create index if not exists market_metadata_updated_at_idx
    on market_metadata (updated_at desc);
