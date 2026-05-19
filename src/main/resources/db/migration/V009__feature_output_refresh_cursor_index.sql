create index if not exists feature_outputs_created_cursor_idx
    on feature_outputs (created_at, feature_event_id, feature_name);
