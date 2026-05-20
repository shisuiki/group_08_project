alter table latest_market_state
    add column if not exists last_canonical_commit_seq bigint;

create index if not exists latest_market_state_commit_seq_idx
    on latest_market_state (last_canonical_commit_seq desc);

create index if not exists latest_market_state_commit_market_idx
    on latest_market_state (last_canonical_commit_seq asc, market_ticker asc);

create index if not exists latest_market_state_updated_market_idx
    on latest_market_state (updated_at asc, market_ticker asc);
