create index if not exists canonical_events_live_created_at_idx
    on canonical_events (created_at desc)
    where replay_id is null;
