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
  -> KalshiIngressEnvelope byte[] JSON envelope
  -> ClientClusterOrchestrator.writeToCluster(byte[])
  -> ESBClusteredService.onSessionMessage scratch byte[] slice
  -> DataProcessor.processMessage(byte[], offset, length)
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
  Evidence: WebSocket frame bytes still become `String` for the raw Kalshi
  payload, and `KalshiCanonicalParser` still parses that raw payload string.
  Status: live ingress and raw replay now send `KalshiIngressEnvelope` as
  byte[]; the ESB leader reuses a scratch byte[] and parses byte[] slices
  without allocating a full envelope string. `Tickerplant` full-tree routing
  parse has been optimized, but routing still depends on payload-carried stream
  metadata. FeaturePlant live Aeron input parses canonical envelopes from
  byte[] via `CanonicalEnvelope.fromPayloadBytes` while retaining the
  `payload()` string API; its live source fair-polls stream subscriptions across
  calls so later streams still receive polling turns when earlier streams are
  busy. DB and recording sources still read stored strings.
  Fix: keep the lightweight routing path; long term, carry stream id/name in a
  header so `Tickerplant` can route without JSON payload inspection.

- [High] Some downstream storage paths are still file-backed.
  Impact: recording/export/debug/import paths still use files; live FeaturePlant,
  frontend adapter, and research export have moved to DB by default.
  Evidence: `RawIngestRecorder`, `TickerplantStreamRecorder`,
  `RecordingCanonicalEnvelopeSource`, and `RawRecordingReader` are retained for
  explicit file/NDJSON workflows.
  Fix: keep default readers on DB and keep runtime NDJSON as explicit
  capture/debug/import/export only.

- [High] Best-effort DB conflicts with "complete audit" language.
  Impact: if DB queue drops, DB history is permanently incomplete.
  Fix: document DB as primary query/audit store for accepted rows only; expose
  `db_*_dropped_total`, `processor_db_offers_total`, gaps, and watermarks.

- [Medium, partially resolved] Hot path does expensive ID and numeric work.
  Impact: CPU and allocation overhead on high-frequency orderbook deltas.
  Evidence: raw event id still uses SHA-256; order book uses boxed `TreeMap`.
  Status: common price/quantity decimal strings now use a fixed-point fast path
  with `BigDecimal` fallback for non-simple formats, and canonical event id part
  sanitization uses a deterministic char loop instead of regex.
  Fix: decide whether source sequence based event ids can replace hot
  `rawEventId` hashing where available; consider primitive price-level
  arrays/maps.

- [Medium, partially resolved] Metrics are too expensive for per-message hot path.
  Impact: each call allocates label maps and metric key strings.
  Evidence: uncached metrics call sites still rebuild labels/keys in remaining
  recorder and tooling paths.
  Status: live `DataProcessor` and `AeronEventPublisher` handles are cached, and
  their hot-path latency/age/backpressure distributions are sampled first-event
  plus every 64th event. Success, failure, drop, parser error, and order book
  quality counters remain exact. Generated `sequence_gap` events, including
  `crossed_book`, now count producer-side
  `backend_orderbook_sequence_gap_total` / `backend_orderbook_crossed_total`.
  `BackendMetrics` handles resolve storage once, `FeaturePlantService` caches
  module/stream metric handles, and unused resolved handles stay absent from
  `snapshot()` and Prometheus output until used.
  Fix: keep sampling distribution-only, precompute remaining metric keys,
  aggregate in counters, export histograms/quantiles outside the hot path.

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
  - suppress unsafe derived order-book apply on source anomalies while raw and
    canonical publication continues
  - count all drops and generated recovery events

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
  - `raw_ws_events` (landed)
  - `canonical_events` (landed)
  - `latest_market_state`
  - `feature_outputs`
- Landed: `AsyncDbWriter` uses split bounded raw/canonical queues and JDBC
  batch insert.
- Landed: `processor_db_offers_total` counts accepted, dropped-full, and
  disabled DB offers by processor path, event type, and stream.
- `RawEventStore`, `CanonicalEventStore`, `FeatureOutputStore`.
- `ON CONFLICT DO NOTHING` for idempotent inserts.
- Drop-on-full metrics.

Placement:

- Raw DB copy: `KalshiWebSocketClient.onMessage`, after live path acceptance,
  using non-blocking queue offer.
- Canonical DB copy: currently enqueued in `DataProcessor` after publication and
  never waits.
- Latest state: DB consumer/query layer, not cluster leader hot path.

Verification:

- Queue full does not block WebSocket path.
- DB down does not block live path.
- Duplicate inserts collapse.
- Dropped rows are visible.

### Batch 5: Reduce JSON And Allocation

Deliverables:

- Landed: live wrapper and raw replay write JSON ingress envelopes as byte[];
  leader-side parsing uses a reusable scratch buffer and byte[] slice parser.
- Landed: FeaturePlant live Aeron source parses canonical envelope JSON from
  byte[] and still retains the payload string for compatibility.
- Landed: FeaturePlant live Aeron source fair-polls subscriptions across calls;
  coverage uses fake pollers, not a live Aeron integration test.
- Remaining: replace JSON ingress envelope with length-prefixed/binary envelope
  or direct raw bytes plus metadata header.
- Add stream id/name header so `Tickerplant` can eventually route from metadata
  instead of payload inspection; full-tree parse optimization is handled.
- Avoid remaining `byte[] -> String -> byte[]` round trips where possible.
- Landed: common hot decimal strings use fixed-point parsing with `BigDecimal`
  fallback for compatibility.
- Landed: event id sanitization uses deterministic low-allocation char loops.
- Move SHA-256 to DB/audit sink, not mandatory processor path.

Verification:

- `processor-serialize` p99 improves against Batch 1 baseline.
- Allocation rate drops in JFR.

### Batch 6: Optimize Order Book And Recovery

Deliverables:

- Keep current `TreeMap` as correctness baseline.
- Landed: `SourceSequenceMonitor` keeps a monotonic subscription watermark:
  forward gaps emit/advance, duplicate or older sequence values emit
  `non_monotonic_source_sequence` without rewinding.
- Landed: `OrderBookState` treats forward source sequence skips as valid
  subscription-wide interleaving, but duplicate/backward per-market sequences
  pause before mutation.
- Landed: source-sequence anomalies on order book deltas still publish raw,
  source gap, and canonical events, but skip derived order-book apply and mark
  the market paused until a fresh snapshot. Disabled derived mode does not
  create order-book state.
- Landed: crossed books suppress the invalid crossed `top_of_book_update`, emit
  `SequenceGapEvent(reason="crossed_book")`, count
  `backend_orderbook_crossed_total` from the sequence-gap labels, and pause.
- Landed: `SourceSequenceMonitor` watermarks can snapshot/restore without
  rewinding on duplicate or older source sequences.
- Landed: `OrderBookStateManager` exports recovery checkpoints; restored books
  fail closed in paused state and do not restore price levels/depth.
- Landed: `DataProcessorRecoveryState` and `ESBClusterSnapshotCodec` v1 encode
  source watermarks plus order-book recovery checkpoints.
- Landed: `ESBClusteredService` writes/loads recovery snapshot payloads and
  loads snapshots before starting tickerplant/demo clients.
- Landed: cluster snapshot publication offer fails fast on terminal Aeron
  statuses and uses bounded retry for backpressure/admin action.
- Remaining: automated fresh snapshot reload/live `get_snapshot` actuator,
  reconnect/subscription restore, and any full-depth book restore.
- Add primitive/discrete price-level book implementation for Kalshi price
  levels.
- Benchmark snapshot/delta/top-of-book update cost.

Verification:

- Same tests pass for both implementations.
- Primitive book wins on high-frequency delta benchmark.

### Batch 7: Move Readers To DB

Deliverables:

- `TimescaleRawReplaySource` is the default raw replay source. (landed)
- `LocalNdjsonRawReplaySource` remains explicit import/debug mode. (landed)
- Raw replay publishes byte[] ingress envelopes with `replay_id`. (landed)
- `DbCanonicalEnvelopeSource` for featureplant/research. (landed)
- `FrontendQueryStore` for `/symbols`, `/quotes`, `/datafeed/history`.
- File readers moved to legacy import/test fixture mode.

Rules:

- query APIs expose gaps/watermarks.
- history/bars tolerate missing rows.
- replay rows use `replay_id`; default replay does not update latest live state.

Verification:

- frontend demo works with DB only. (landed)
- featureplant can run from DB source. (landed)
- research export can run from DB source. (landed)

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
5. Move raw event id SHA-256 off the mandatory processor path where possible.
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
3. Whether to keep the canonical DB sink inside `DataProcessor` or move it to a
   downstream Aeron consumer.
4. Whether raw event id should use payload hash, source sequence, or
   connection sequence.
5. Whether S3 archive export remains in scope after DB migration.

## First Implementation Slice

Smallest useful slice:

1. Maven Wrapper is present.
2. Perf baseline script for `HotPathProfileCli` is present.
3. Default single-offer/drop-first ingress with drop metrics is landed.
4. `AsyncDbWriter` has split raw/canonical bounded queues and drop metrics.
5. DB migrations for `raw_ws_events` and `canonical_events` are landed.
6. Tests cover duplicate insert and queue overflow.

This slice proves the latency-first contract without rewriting the full system.
