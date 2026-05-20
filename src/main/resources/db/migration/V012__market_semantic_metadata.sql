create table if not exists market_semantic_metadata (
    market_ticker text not null,
    taxonomy_version text not null,
    model text not null,
    prompt_version text not null,
    prompt_hash text not null,
    source_payload_sha256 text not null,
    source_fingerprint text not null,
    idempotency_key text not null,
    sector text,
    subsector text,
    event_type text,
    region text,
    time_horizon text,
    liquidity_bucket text,
    risk_bucket text,
    tags jsonb not null default '[]'::jsonb,
    confidence numeric,
    rationale text,
    raw_response jsonb not null,
    status text not null,
    error text,
    generated_at timestamptz,
    updated_at timestamptz not null default now(),
    primary key (market_ticker, taxonomy_version),
    constraint market_semantic_metadata_status_chk
        check (status in ('generated', 'review_required', 'failed', 'rate_limited')),
    constraint market_semantic_metadata_confidence_chk
        check (confidence is null or (confidence >= 0 and confidence <= 1))
);

create unique index if not exists market_semantic_metadata_prompt_uidx
    on market_semantic_metadata (market_ticker, taxonomy_version, prompt_hash);

create unique index if not exists market_semantic_metadata_idempotency_uidx
    on market_semantic_metadata (idempotency_key);

create index if not exists market_semantic_metadata_status_idx
    on market_semantic_metadata (taxonomy_version, status, updated_at desc);

create index if not exists market_semantic_metadata_generated_at_idx
    on market_semantic_metadata (generated_at desc);

create table if not exists market_semantic_metadata_jobs (
    job_id text primary key,
    market_ticker text not null,
    taxonomy_version text not null,
    prompt_hash text not null,
    source_payload_sha256 text not null,
    source_fingerprint text not null,
    idempotency_key text not null,
    requested_model text not null,
    actual_model text,
    status text not null,
    attempts integer not null default 0,
    next_retry_at timestamptz,
    error text,
    usage jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint market_semantic_metadata_jobs_status_chk
        check (status in ('pending', 'running', 'generated', 'review_required', 'failed', 'rate_limited')),
    constraint market_semantic_metadata_jobs_attempts_chk
        check (attempts >= 0)
);

create unique index if not exists market_semantic_metadata_jobs_market_taxonomy_prompt_uidx
    on market_semantic_metadata_jobs (
        market_ticker,
        taxonomy_version,
        prompt_hash,
        source_fingerprint,
        requested_model
    );

create unique index if not exists market_semantic_metadata_jobs_idempotency_uidx
    on market_semantic_metadata_jobs (idempotency_key);

create index if not exists market_semantic_metadata_jobs_status_retry_idx
    on market_semantic_metadata_jobs (status, next_retry_at, updated_at);
