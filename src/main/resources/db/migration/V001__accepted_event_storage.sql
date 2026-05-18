create table if not exists raw_ws_events (
    raw_event_id text primary key,
    source text not null,
    capture_id text not null,
    connection_id text not null,
    connection_sequence bigint not null,
    receive_ts_ns bigint not null,
    receive_wall_ts timestamptz not null,
    market_ticker text,
    source_channel text,
    source_sequence bigint,
    payload_sha256 text not null,
    raw_payload text not null,
    ingest_status text not null default 'stored',
    created_at timestamptz not null default now()
);

create index if not exists raw_ws_events_receive_ts_ns_idx
    on raw_ws_events (receive_ts_ns);

create index if not exists raw_ws_events_market_ticker_receive_ts_ns_idx
    on raw_ws_events (market_ticker, receive_ts_ns);

create index if not exists raw_ws_events_connection_sequence_idx
    on raw_ws_events (connection_id, connection_sequence);

create unique index if not exists raw_ws_events_payload_identity_uidx
    on raw_ws_events (payload_sha256, receive_ts_ns, connection_id, connection_sequence);

create table if not exists canonical_events (
    event_id text primary key,
    raw_event_id text,
    replay_id text,
    stream_name text not null,
    event_type text not null,
    schema_version integer not null,
    market_ticker text,
    event_ts_ms bigint,
    ingest_ts_ns bigint,
    publish_ts_ns bigint,
    payload jsonb not null,
    created_at timestamptz not null default now()
);

create index if not exists canonical_events_stream_event_ts_idx
    on canonical_events (stream_name, event_ts_ms);

create index if not exists canonical_events_market_event_ts_idx
    on canonical_events (market_ticker, event_ts_ms);

create index if not exists canonical_events_raw_event_id_idx
    on canonical_events (raw_event_id);

create index if not exists canonical_events_replay_id_idx
    on canonical_events (replay_id);
