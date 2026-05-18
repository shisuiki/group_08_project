do $$
begin
    if exists (
        select 1
        from information_schema.columns
        where table_schema = current_schema()
          and table_name = 'raw_ws_events'
          and column_name = 'raw_payload'
          and data_type <> 'text'
    ) then
        alter table raw_ws_events
            alter column raw_payload type text using raw_payload::text;
    end if;
end $$;
