create table if not exists featureplant_cursors (
    cursor_name text primary key,
    last_commit_seq bigint not null,
    updated_at timestamptz not null default now(),
    constraint featureplant_cursors_last_commit_seq_nonnegative check (last_commit_seq >= 0)
);

create index if not exists featureplant_cursors_updated_at_idx
    on featureplant_cursors (updated_at);
