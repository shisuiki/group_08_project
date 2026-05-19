alter table canonical_events
    alter column event_id set not null;

create unique index if not exists canonical_events_event_replay_id_uidx
    on canonical_events (event_id, replay_id) nulls not distinct;

alter table canonical_events
    drop constraint if exists canonical_events_pkey;
