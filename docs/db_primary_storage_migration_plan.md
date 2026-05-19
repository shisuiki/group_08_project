# DB Primary Storage Migration Plan

## Terms

S3 means Amazon Simple Storage Service.

In the new design, database storage is the primary query and audit store, but it
must not block the live hot path. Live data loss is acceptable when downstream
consumers or storage cannot keep up. Latency has priority over completeness.

NDJSON fallback is removed from the target design. S3 is optional cold archive
fed from the database, not a runtime fallback path.

## Target Contract

Primary storage contract:

1. WebSocket receive and Aeron publication must not wait on database commits.
2. The database writer is asynchronous and best-effort, with bounded buffering.
3. When buffers are full or DB is unavailable, DB writes may be dropped.
4. Every database row has a stable idempotency key.
5. Replays and retries may deliver duplicates, but duplicate side effects must
   collapse at the database sink.
6. Readers only consume committed database state plus explicit gap/watermark
   signals.
7. Missing rows are expected and must be observable.

Target storage priority:

```text
Live WebSocket
  -> Aeron hot path first
  -> async bounded DB writer
  -> query API / replay / featureplant / frontend
  -> optional S3 archive/export from DB
```

## Recommended Database

Use Postgres with TimescaleDB.

Reasons:

- high write rate time-series events
- SQL querying for demo and research
- raw payload text for exact audit retention and JSONB canonical payloads during schema transition
- hypertables for timestamp partitioning
- unique constraints for idempotent inserts
- materialized views for bars/features later

## Core Tables

### `raw_ws_events`

Authoritative raw websocket input for events accepted by the DB writer. The
live hot path may publish events that never reach this table during overload or
DB outage.

Required columns:

- `raw_event_id text primary key`
- `source text not null`
- `capture_id text not null`
- `connection_id text not null`
- `connection_sequence bigint not null`
- `receive_ts_ns bigint not null`
- `receive_wall_ts timestamptz not null`
- `market_ticker text`
- `source_channel text`
- `source_sequence bigint`
- `payload_sha256 text not null`
- `raw_payload text not null`
- `ingest_status text not null default 'stored'`
- `created_at timestamptz not null default now()`

Indexes:

- `(receive_ts_ns)`
- `(market_ticker, receive_ts_ns)`
- `(connection_id, connection_sequence)`
- unique `(payload_sha256, receive_ts_ns, connection_id, connection_sequence)`

`raw_payload` stores the exact websocket payload string as text so audit rows can
preserve malformed or non-JSON input. Parsed metadata lives in dedicated columns;
canonical event payloads remain JSONB.

### `raw_rest_responses`

Authoritative raw historical REST response storage for backfill runs that keep
the exact response body. This table is separate from `raw_ws_events`, which is
reserved for websocket raw ingest.

Required columns:

- `raw_rest_response_id text primary key`
- `endpoint text not null`
- `ticker text`
- `fetch_ts_ns bigint not null`
- `fetch_wall_ts timestamptz not null`
- `payload_sha256 text not null`
- `raw_payload text not null`
- `created_at timestamptz not null default now()`

Indexes:

- `(endpoint, fetch_ts_ns)`
- `(ticker, fetch_ts_ns)`
- `(payload_sha256)`

The response id is derived from endpoint, ticker, and payload hash so repeated
backfills can use `ON CONFLICT DO NOTHING` without creating unbounded duplicate
raw REST rows.

### `canonical_events`

Target authoritative normalized event log once Phase 4 is complete.

Required columns:

- `event_id text primary key`
- `raw_event_id text`
- `replay_id text`
- `stream_name text not null`
- `event_type text not null`
- `schema_version int not null`
- `market_ticker text`
- `event_ts_ms bigint`
- `ingest_ts_ns bigint`
- `publish_ts_ns bigint`
- `payload jsonb not null`
- `created_at timestamptz not null default now()`

Indexes:

- `(stream_name, event_ts_ms)`
- `(market_ticker, event_ts_ms)`
- `(raw_event_id)`
- `(replay_id)`

`raw_event_id` is nullable linking metadata, not a foreign key. The DB writer is
asynchronous and best-effort, so raw and canonical queues may drop independently
or commit out of order. Enforcing an FK would turn a permitted gap or race into
a canonical insert failure.

### `stream_offsets`

Tracks consumer progress for DB-backed processors.

Required columns:

- `consumer_name text not null`
- `stream_name text not null`
- `last_event_id text`
- `last_receive_ts_ns bigint`
- `updated_at timestamptz not null default now()`
- primary key `(consumer_name, stream_name)`

### `market_metadata`

Market/event/series metadata from REST discovery/backfill.

Required columns:

- `market_ticker text primary key`
- `event_ticker text`
- `series_ticker text`
- `status text`
- `open_time timestamptz`
- `close_time timestamptz`
- `settlement_time timestamptz`
- `rules_payload jsonb`
- `market_payload jsonb not null`
- `updated_at timestamptz not null default now()`

### `feature_outputs`

Durable feature store schema and isolated JDBC store boundary.

Required columns:

- `feature_event_id text primary key`
- `source_event_id text`
- `feature_name text not null`
- `feature_version int not null`
- `market_ticker text`
- `event_ts_ms bigint`
- `values jsonb not null`
- `created_at timestamptz not null default now()`

Indexes:

- `(feature_name, market_ticker, event_ts_ms)`
- unique `(feature_name, feature_version, source_event_id, market_ticker)`

### `latest_market_state`

Read-optimized latest state.

Required columns:

- `market_ticker text primary key`
- `last_event_ts_ms bigint`
- `last_canonical_event_id text`
- `best_bid_micros bigint`
- `best_ask_micros bigint`
- `midpoint_micros bigint`
- `open_interest bigint`
- `payload jsonb`
- `updated_at timestamptz not null default now()`

Update rule:

```sql
update only when incoming.event_ts_ms >= current.last_event_ts_ms
```

## Write Path

### Phase 1 Target

```text
KalshiWebSocketClient
  -> Aeron Cluster ingress
  -> DataProcessor
  -> Tickerplant publish
  -> AsyncDbWriter receives raw/canonical copies from non-blocking queues
  -> batch insert raw_ws_events and canonical_events
```

The hot path must not wait for DB commit.

### Why The Outbox Is Removed

The previous design used a transactional outbox so database durability could
drive Aeron publication. That is the wrong priority for this project if latency
comes first and live gaps are acceptable.

New rule:

- Aeron hot path owns live delivery.
- DB writer is a downstream sink.
- DB failure creates data gaps, not backpressure on live publication.
- Gaps must be counted and visible.

## Read/Write Race Rules

### Duplicate Ingest

Cause:

- websocket reconnect
- producer retry
- DB import/replay jobs
- operator rerun

Rule:

- `raw_event_id` and payload hash constraints dedupe raw writes.
- inserts use `ON CONFLICT DO NOTHING`.

### DB Writer Lag

Cause:

- database batch writer cannot keep up

Rule:

- DB queues are bounded.
- after high watermark, drop DB writes instead of slowing Aeron.
- increment dropped counters by stream/event type.
- expose writer lag and queue depth.

### Multiple DB Writers

Cause:

- multiple async writers insert the same event after retries or restarts

Rule:

- deterministic IDs and unique constraints dedupe rows.
- batch insert uses `ON CONFLICT DO NOTHING`.
- no distributed lock is required for append-only inserts.
- DB cursor reads currently assume one canonical DB writer. Before allowing
  concurrent canonical writers/importers, add an explicit committed watermark or
  serialize canonical insert transactions so readers cannot advance past a
  lower sequence that has not committed yet.

### Canonical Write vs Frontend Read

Cause:

- frontend queries while canonical event batch is partially written

Rule:

- readers only see committed rows.
- chart/history queries read up to a committed watermark.
- chart/history queries must tolerate missing buckets.
- current/open bar may be marked provisional.
- closed bars are final only after bucket end plus lag window.

### Backfill vs Live Data

Cause:

- REST backfill inserts older events while live ingestion writes newer events

Rule:

- canonical event identity is source/event based, not insertion-time based.
- canonical DB cursor is a database-assigned insert sequence for committed-row
  resume under the current single-writer path; it must not replace event
  identity or event-time ordering.
- latest-state updates use event timestamp fences.
- backfill may fill historical gaps but must not overwrite newer latest state.

### Replay vs Live Data

Cause:

- raw replay injects old payloads into live processor

Rule:

- replay writes carry `replay_id`.
- default replay canonical rows are queryable by `replay_id`.
- replay must not update `latest_market_state` unless explicitly run in
  "stateful replay" mode.

### Feature Output Race

Cause:

- featureplant reprocesses same canonical event after restart

Rule:

- `feature_outputs` uses unique
  `(feature_name, feature_version, source_event_id, market_ticker)`.
- retry inserts are idempotent.
- changed feature logic increments `feature_version`.

## NDJSON Legacy/Capture Boundary

NDJSON is removed from the default runtime path. It remains explicit
capture/import/export/fixture/demo/debug tooling.

Allowed roles:

- existing demo fixtures until DB-backed demo data exists
- one-time import source for historical recordings already created
- explicit recording-capture raw/canonical output
- explicit raw REST recording/export/debug when
  `HISTORICAL_BACKFILL_RAW_REST_TARGET=recording`
- manual debug export from DB, not an ingestion fallback

Not allowed:

- fallback spool during DB outage
- primary replay source
- hot-path archive writer
- source of truth for new live captures

## S3 Role After Migration

S3 is optional archive/cold storage for explicit capture/export/import
workflows. Normal live replay, FeaturePlant, frontend, and research sources use
Timescale/Postgres.

Allowed:

- periodic export of DB partitions
- long-term audit archive
- course/demo artifact sharing

Not allowed:

- hot frontend query source
- primary replay source
- only copy of accepted raw websocket input

## Migration Phases

### Phase 0: Contract And Config

Deliverables:

- configure async DB writer with `DB_WRITER_ENABLED`,
  `DB_WRITER_DATABASE_URL`, `DB_WRITER_DATABASE_USER`, and
  `DB_WRITER_DATABASE_PASSWORD`
- define bounded writer behavior with `DB_WRITER_QUEUE_CAPACITY`,
  `DB_WRITER_BATCH_SIZE`, and drop/failure metrics

Exit criteria:

- docs state DB is the primary query/audit store, not a hot-path gate
- file/S3 runtime fallback is removed
- operators know DB failure causes query gaps, not live blocking

### Phase 1: Schema And Local DB

Deliverables:

- SQL migrations under `src/main/resources/db/migration`
- Docker Compose `timescaledb` service
- migration runner script or Flyway/Liquibase plugin
- tests for uniqueness and basic inserts

Exit criteria:

- empty DB can be created from repo
- V001 creates `raw_ws_events` and `canonical_events`
- V005 creates `latest_market_state`
- CI validates migrations

### Phase 2: Storage Interfaces

Deliverables:

- `RawEventStore` interface
- `CanonicalEventStore` interface
- `AsyncDbWriter`
- bounded in-memory write queues
- DB implementations

Required behavior:

- DB stores batch inserts.
- DB stores expose idempotent insert results:
  - inserted
  - duplicate
  - failed
  - dropped

Exit criteria:

- unit tests cover duplicate raw/canonical writes
- unit tests cover queue overflow/drop behavior

### Phase 3: Raw Async DB Sink

Deliverables:

- `KalshiWebSocketClient` publishes to Aeron without waiting for DB
- `KalshiWebSocketClient` enqueues raw payload copies to `AsyncDbWriter`
- raw DB writer batch inserts `raw_ws_events`
- queue overflow drops DB writes and increments metrics

Failure tests:

- duplicate websocket payload
- DB unavailable
- DB writer queue full
- process dies with queued but unflushed events
- two writers insert same event

Exit criteria:

- live publication is not blocked by DB
- `cluster-live` no longer starts the NDJSON/S3 recorder path by default
- dropped DB writes are visible in metrics
- duplicate raw writes produce one DB row

### Phase 4: Canonical Async DB Sink

Deliverables:

- `DataProcessor` enqueues canonical event copies to `AsyncDbWriter`
- canonical event ids are deterministic
- `latest_market_state` upsert is timestamp-fenced
- tickerplant publish does not wait for canonical DB insert

Failure tests:

- same raw event processed twice
- older backfill event arrives after newer live event
- replay event with `replay_id` does not update latest state by default
- canonical DB queue full

Exit criteria:

- canonical DB rows exist for events the async writer accepted
- duplicate processing does not duplicate canonical rows
- dropped canonical DB writes are visible in metrics

### Phase 5: Readers Move To DB

Baseline delivered:

- raw replay defaults to Timescale/Postgres, with
  `RAW_REPLAY_SOURCE=local-ndjson` for explicit import/debug.
- FeaturePlant, frontend adapter, and research export default to canonical DB
  rows.

Remaining:

- finish DB query/API migration for raw/canonical data.
- keep recording import/export boundaries explicit.
- seed demo data through DB imports.

### Phase 6: S3 Archive Export

Deliverables:

- DB export job
- partitioned S3 upload from DB exports
- checksum manifest
- optional import job from S3 archive for offline recovery

Exit criteria:

- S3 object is reproducible from DB partition
- S3 is no longer required for normal frontend/replay operation

### Phase 7: Cleanup

Deliverables:

- README status table updated
- old file-primary language removed
- `backend_storage_schema.md` replaced or amended
- CI covers DB migrations and DB-backed smoke tests

Exit criteria:

- docs no longer imply NDJSON/S3 is primary
- file-primary mode is removed or marked legacy-only

## Verification Matrix

| Scenario | Expected result |
| --- | --- |
| duplicate raw payload | one `raw_ws_events` row |
| DB writer queue full | live path continues, DB drop metric increments |
| DB unavailable | live path continues, DB failure/drop metrics increment |
| process dies with queued DB writes | queued writes may be lost; live path contract still holds |
| live newer event then backfill older event | latest state remains newer |
| replay old event | replay rows queryable by `replay_id`, latest unchanged |
| two featureplant workers | one feature output per unique feature key |
| frontend reads during writes | sees committed rows only; open bars provisional |

## Metrics

DB-primary live storage is observed through processor DB offers and DB writer
metrics:

- `processor_db_offers_total`
- `db_raw_events_accepted_total`
- `db_raw_events_dropped_total`
- `db_raw_events_written_total`
- `db_canonical_events_accepted_total`
- `db_canonical_events_dropped_total`
- `db_canonical_events_written_total`
- `db_writer_batch_failed_total`
- `db_writer_queue_depth`
- `db_writer_raw_queue_depth`
- `db_writer_canonical_queue_depth`

## Configuration

DB writer configuration is parsed in one place. Blank or unset
`DB_WRITER_ENABLED` auto-enables the writer when `DB_WRITER_DATABASE_URL` is
present; set `DB_WRITER_ENABLED=false` to opt out explicitly:

- `DB_WRITER_ENABLED` blank/unset means auto
- `DB_WRITER_DATABASE_URL` default empty
- `DB_WRITER_DATABASE_USER` default empty
- `DB_WRITER_DATABASE_PASSWORD` default empty
- `DB_WRITER_QUEUE_CAPACITY` default `250000`
- `DB_WRITER_BATCH_SIZE` default `500`
- `DB_WRITER_RAW_SOURCE` default `kalshi.websocket`
- `DB_WRITER_RAW_CAPTURE_ID` default `live`

These settings are plumbed through `.env.example`, Docker Compose, and the EC2
deploy workflow. EC2 deployments read `DB_WRITER_DATABASE_PASSWORD` from the
optional GitHub secret of the same name. DB writes remain async, non-blocking,
and drop-visible through writer metrics.

## Open Decisions

1. What queue capacity is acceptable before DB writes are dropped?
2. Should DB writer drop newest or oldest events when full?
3. Is replay allowed to update latest state in any mode?
4. Should feature store be normalized columns, JSONB, or hybrid?
5. What retention, checksum, and restore policy should S3 archive exports use?
6. Should the store API report inserted versus duplicate rows, or should duplicate
   accounting stay in the later JDBC integration layer?

## Completed Batches

- V001/V002/V003/V004/V005/V006/V007 migrations exist for raw websocket events, canonical
  events, canonical commit cursoring, raw payload text storage, and raw REST
  response capture, timestamp-fenced `latest_market_state`, and
  `feature_outputs` plus `market_metadata`.
- `AsyncDbWriter`, bounded DB writer queues, raw/canonical JDBC writes, raw REST
  JDBC storage, canonical DB reads, and DB-default replay/feature/frontend/export
  paths exist.

## Remaining Next Batches

1. Finish DB query/API migration and DB-seeded demo data.
2. Define the S3 archive/import/export retention and restore policy.
