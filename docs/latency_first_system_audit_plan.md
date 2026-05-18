# Latency-First System Audit Plan

## Decisions

Current product stance:

- Live latency has priority over completeness.
- Dropping live data is acceptable when buffers, Aeron, DB, or sinks cannot keep
  up.
- DB is the primary query/audit store for rows it accepts, not a hot-path
  durability gate.
- NDJSON runtime fallback should be removed.
- S3 is optional archive/export, not a live dependency.

Implication:

- The system must be fail-open for live publication.
- Data gaps are acceptable, but they must be measured and visible.
- Query APIs must expose gaps/watermarks instead of pretending history is
  complete.

## Current Hot Path

```text
Kalshi WebSocket
  -> WebSocketClient frame parser
  -> KalshiWebSocketClient.onMessage
  -> optional RawIngestRecorder
  -> JSON ingress envelope
  -> ClientClusterOrchestrator.writeToCluster
  -> ESBClusteredService.onSessionMessage
  -> DataProcessor
  -> KalshiCanonicalParser
  -> OrderBookStateManager
  -> AeronEventPublisher
  -> internal Aeron stream
  -> Tickerplant
  -> external Aeron streams
```

## Findings

- [High, resolved] `ClientClusterOrchestrator.writeToCluster` no longer retries
  cluster ingress by default on the WebSocket receive path.
  Impact: cluster ingress backpressure stalls live ingestion.
  Status: live ingress now defaults to one Aeron offer and drops/counts on
  failure. Prefer one ingress client per WebSocket shard or a non-shared buffer
  as the next bottleneck reduction.

- [High] The same message is parsed/copied too many times.
  Impact: avoidable latency and allocation.
  Evidence: WebSocket frame bytes become `String`; ingress wraps raw payload in
  JSON; `KalshiIngressEnvelope` parses envelope; `KalshiCanonicalParser` parses
  raw payload; `Tickerplant` parses canonical JSON only to read `stream_name`.
  Fix: use a length-prefixed or binary ingress envelope; carry stream id in a
  header; remove `Tickerplant` JSON routing parse.

- [High] Current storage path is file-first, DB is mostly roadmap.
  Impact: frontend, replay, featureplant, and export still depend on recording
  files.
  Evidence: `RawIngestRecorder`, `TickerplantStreamRecorder`,
  `RecordingCanonicalEnvelopeSource`, `RawRecordingReader`, and
  `ResearchExportCli` are file/NDJSON based.
  Fix: add async DB writer and DB readers; remove runtime NDJSON fallback after
  DB-backed demo works.

- [High] Best-effort DB conflicts with "complete audit" language.
  Impact: if DB queue drops, DB history is permanently incomplete.
  Fix: document DB as primary query/audit store for accepted rows only; expose
  `db_*_dropped_total`, gaps, and watermarks.

- [Medium] Hot path does expensive ID and numeric work.
  Impact: CPU and allocation overhead on high-frequency orderbook deltas.
  Evidence: raw event id uses SHA-256; event id sanitization uses regex;
  price/quantity parsing uses `BigDecimal`; order book uses boxed `TreeMap`.
  Fix: use source sequence based event ids where available; replace hot
  `BigDecimal` parsing with fixed-point string parsing; consider primitive
  price-level arrays/maps.

- [Medium] Metrics are too expensive for per-message hot path.
  Impact: each call allocates label maps and metric key strings.
  Evidence: `BackendMetrics.labels` and `metricKey` run repeatedly in
  `DataProcessor`, `AeronEventPublisher`, `Tickerplant`, recorder, and feature
  code.
  Fix: precompute metric keys, sample latency metrics, aggregate in counters,
  export histograms/quantiles outside the hot path.

- [Medium] File writers still have blocking or expensive behavior.
  Impact: recording can slow downstream consumers and create false confidence
  in file durability.
  Evidence: raw recorder can block when `dropOnFull=false`; recording writers
  open/append/close files per event; canonical recorder parses/deep-copies JSON.
  Fix: remove runtime NDJSON recording from target path; if temporarily kept,
  force drop-on-full and treat it as a lossy side sink.

- [Medium] Health and latency evidence are incomplete.
  Impact: low latency cannot be proven in video, CI, or deployment.
  Evidence: profiler exists, stream-recorder has e2e latency metric, but CI has
  no perf gate; health endpoints mostly report process liveness.
  Fix: add p95/p99 metrics, perf artifacts, readiness gates, and demo scripts.

- [Medium] Architecture and roadmap are mixed.
  Impact: reviewers may confuse planned semantic/pricing/feature-store modules
  with current functionality.
  Fix: keep status docs separate from roadmap; make README lead with
  current/skeleton/planned/removed status.

## Latency-First Repair Plan

### Batch 0: Lock The Contract

Deliverables:

- README status table: current / skeleton / planned / removed.
- State DB as best-effort query/audit store, not complete source of truth.
- State NDJSON runtime fallback is removed.
- Define drop policy:
  - drop DB writes on queue full
  - drop metrics samples under pressure
  - drop derived events before raw/orderbook live publication
  - count all drops

Verification:

- Docs no longer say S3/NDJSON is source of truth for new live captures.
- Demo script does not rely on live credentials or S3.

### Batch 1: Establish Baseline

Deliverables:

- Maven Wrapper.
- Run `HotPathProfileCli` modes:
  - `parse-only`
  - `parse-book`
  - `processor-noop`
  - `processor-serialize`
- Add optional JFR profile command.
- Add perf artifact output in CI.

Verification:

- Baseline p50/p95/p99 recorded before optimization.
- Regressions can be compared across commits.

### Batch 2: Make Ingress Fail-Open

Deliverables:

- Landed: replace unbounded `writeToCluster` wait with default single-offer
  drop-first ingress.
- Landed: add `cluster_ingress_offer_failed_total`.
- Landed: add `cluster_ingress_dropped_total`.
- Remaining: remove shared synchronized ingress bottleneck or use one ingress
  client per WebSocket shard.

Verification:

- Test offer failure does not block caller.
- Test drop counter increments.
- Hot path still runs when cluster is slow.

### Batch 3: Remove Runtime File Writes

Deliverables:

- Landed: decouple `RawIngestRecorder` from default live websocket client
  construction; attach it only through `recording-capture`.
- Stop treating `TickerplantStreamRecorder` as required storage.
- Keep file readers only as legacy import/demo tools.
- Remove `dropOnFull=false` mode from live profiles.

Verification:

- Live profile starts without `recordings` volume.
- No file writer is on the required hot path.
- Stream recorder can be run as optional observer only.

### Batch 4: Add Async DB Sink

Deliverables:

- DB migrations:
  - `raw_ws_events`
  - `canonical_events`
  - `latest_market_state`
  - `feature_outputs`
- `AsyncDbWriter` with bounded queues and JDBC batch insert.
- `RawEventStore`, `CanonicalEventStore`, `FeatureOutputStore`.
- `ON CONFLICT DO NOTHING` for idempotent inserts.
- Drop-on-full metrics.

Placement:

- Raw DB copy: `KalshiWebSocketClient.onMessage`, after live path acceptance,
  using non-blocking queue offer.
- Canonical DB copy: prefer downstream Aeron consumer; if in processor, enqueue
  after publication and never wait.
- Latest state: DB consumer/query layer, not cluster leader hot path.

Verification:

- Queue full does not block WebSocket path.
- DB down does not block live path.
- Duplicate inserts collapse.
- Dropped rows are visible.

### Batch 5: Reduce JSON And Allocation

Deliverables:

- Replace JSON ingress envelope with length-prefixed/binary envelope or direct
  raw bytes plus metadata header.
- Add stream id/name header so `Tickerplant` does not parse JSON to route.
- Avoid `byte[] -> String -> byte[]` round trips where possible.
- Replace hot `BigDecimal` parsing with fixed-point parser.
- Replace regex event id sanitization with deterministic low-allocation logic.
- Move SHA-256 to DB/audit sink, not mandatory processor path.

Verification:

- `processor-serialize` p99 improves against Batch 1 baseline.
- Allocation rate drops in JFR.

### Batch 6: Optimize Order Book

Deliverables:

- Keep current `TreeMap` as correctness baseline.
- Add primitive/discrete price-level book implementation for Kalshi price
  levels.
- Benchmark snapshot/delta/top-of-book update cost.

Verification:

- Same tests pass for both implementations.
- Primitive book wins on high-frequency delta benchmark.

### Batch 7: Move Readers To DB

Deliverables:

- `DbRawReplaySource` becomes default.
- `DbCanonicalEnvelopeSource` for featureplant/research.
- `FrontendQueryStore` for `/symbols`, `/quotes`, `/datafeed/history`.
- File readers moved to legacy import/test fixture mode.

Rules:

- query APIs expose gaps/watermarks.
- history/bars tolerate missing rows.
- replay rows use `replay_id`; default replay does not update latest live state.

Verification:

- frontend demo works with DB only.
- featureplant can run from DB source.
- research export can run from DB source.

### Batch 8: Observability And Demo

Deliverables:

- Real readiness endpoints:
  - recent event age
  - error/drop rates
  - DB writer queue depth
  - symbols count
  - feature errors
- Prometheus p95/p99 or histogram metrics.
- Stable sample DB seed.
- Demo script:
  - seed DB
  - start frontend adapter
  - open chart
  - show `/health`, `/metrics`, profiler output

Verification:

- video demo is reproducible without live Kalshi credentials.
- CI smoke can run DB-backed demo endpoints.

### Batch 9: Structural Cleanup

Deliverables:

- Add `apps.*Main` entrypoints and make Docker compose use only those.
- Move current packages toward these boundaries:
  - `contracts`
  - `ingest`
  - `processing`
  - `runtime.aeron`
  - `storage.db`
  - `replay`
  - `backfill`
  - `feature`
  - `adapters.frontend`
  - `tools`
- Isolate or delete:
  - `library.py`
  - main-source `TestPublisher`
  - runtime import of `MarketGridDemo`
- Keep semantic/pricing/arb in roadmap only.

Verification:

- cluster runtime does not import demo/tools/frontend.
- contracts package has no runtime dependencies.
- README no longer looks like a mixed final report and roadmap.

## Additional Latency Options

Ranked by expected impact:

1. One ingress client per WebSocket shard.
2. Remove JSON envelope double parse.
3. Header-based tickerplant routing.
4. Precomputed metric keys and sampled latency metrics.
5. Fixed-point parser instead of `BigDecimal`.
6. Primitive price-level order book.
7. Binary canonical serialization for internal streams.
8. Separate CPU-pinned threads for WebSocket, cluster ingress, processor,
   tickerplant, and DB writer.
9. GC tuning only after allocation reductions are measured.

Do not start with GC flags, S3, or DB tuning. Current code-level allocation and
blocking points are more obvious and cheaper to fix first.

## Decision Gates

Must be decided before implementation:

1. DB queue capacity and drop policy: drop newest or oldest.
2. Which events are allowed to drop first:
   - DB writes
   - metrics
   - derived top-of-book
   - raw/canonical live publication
3. Whether canonical DB sink runs inside `DataProcessor` or as downstream
   Aeron consumer.
4. Whether raw event id should use payload hash, source sequence, or
   connection sequence.
5. Whether S3 archive export remains in scope after DB migration.

## First Implementation Slice

Smallest useful slice:

1. Add Maven Wrapper.
2. Add perf baseline script for `HotPathProfileCli`.
3. Land default single-offer/drop-first ingress with drop metrics.
4. Add `AsyncDbWriter` skeleton with bounded queue and drop metrics.
5. Add DB migrations for `raw_ws_events` and `canonical_events`.
6. Add tests for duplicate insert and queue overflow.

This slice proves the latency-first contract without rewriting the full system.
