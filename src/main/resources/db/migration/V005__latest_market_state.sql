create table if not exists latest_market_state (
    market_ticker text primary key,
    last_event_ts_ms bigint,
    last_canonical_event_id text,
    best_bid_micros bigint,
    best_ask_micros bigint,
    midpoint_micros bigint,
    open_interest bigint,
    payload jsonb,
    updated_at timestamptz not null default now()
);

create index if not exists latest_market_state_last_event_ts_ms_idx
    on latest_market_state (last_event_ts_ms desc);

create index if not exists latest_market_state_updated_at_idx
    on latest_market_state (updated_at desc);
