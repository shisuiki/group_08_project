create sequence if not exists canonical_events_commit_seq_seq;

alter table canonical_events
    add column if not exists canonical_commit_seq bigint;

alter sequence canonical_events_commit_seq_seq
    owned by canonical_events.canonical_commit_seq;

alter table canonical_events
    alter column canonical_commit_seq set default nextval('canonical_events_commit_seq_seq'::regclass);

update canonical_events
set canonical_commit_seq = nextval('canonical_events_commit_seq_seq'::regclass)
where canonical_commit_seq is null;

select setval(
    'canonical_events_commit_seq_seq'::regclass,
    greatest(coalesce((select max(canonical_commit_seq) from canonical_events), 1), 1),
    (select max(canonical_commit_seq) is not null from canonical_events)
);

alter table canonical_events
    alter column canonical_commit_seq set not null;

create unique index if not exists canonical_events_commit_seq_uidx
    on canonical_events (canonical_commit_seq);

create index if not exists canonical_events_stream_commit_seq_idx
    on canonical_events (stream_name, canonical_commit_seq);

create index if not exists canonical_events_market_event_ts_commit_seq_idx
    on canonical_events (market_ticker, event_ts_ms, canonical_commit_seq);

create index if not exists canonical_events_replay_commit_seq_idx
    on canonical_events (replay_id, canonical_commit_seq);
