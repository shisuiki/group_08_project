create table if not exists raw_rest_responses (
    raw_rest_response_id text primary key,
    endpoint text not null,
    ticker text,
    fetch_ts_ns bigint not null,
    fetch_wall_ts timestamptz not null,
    payload_sha256 text not null,
    raw_payload text not null,
    created_at timestamptz not null default now()
);

create index if not exists raw_rest_responses_endpoint_fetch_ts_ns_idx
    on raw_rest_responses (endpoint, fetch_ts_ns);

create index if not exists raw_rest_responses_ticker_fetch_ts_ns_idx
    on raw_rest_responses (ticker, fetch_ts_ns);

create index if not exists raw_rest_responses_payload_sha256_idx
    on raw_rest_responses (payload_sha256);
