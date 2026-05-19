# Project State Report

## Scope

This file separates current implementation from roadmap items. Do not treat
planned modules as shipped features.

Sources checked:

- `diagram.png`
- `README.md`
- `docs/current_and_planned_architecture.md`
- `docs/backend_storage_schema.md`
- `docs/backend_runbook.md`
- `docs/featureplant_runbook.md`
- `docs/frontend_adapter_runbook.md`
- `docker-compose.yml`
- `src/main/java`
- `src/test/java`

## Purpose

The project is a Kalshi market-data pipeline foundation.

It is not a trading/order-entry system. It is not yet a full feature/query,
semantic, pricing, or arbitrage platform.

Current core promise:

1. Collect Kalshi REST/WebSocket market data.
2. Convert raw payloads into canonical market-data events.
3. Distribute events through Aeron Cluster and tickerplant streams.
4. Store accepted raw/canonical rows in Timescale/Postgres for replay,
   research, frontend, and FeaturePlant defaults.
5. Run simple feature templates and a TradingView-style demo adapter.

## Legacy Diagram Notes

`diagram.png` shows the original baseline architecture:

- Kalshi connects to a Kalshi Data Collection Service over WebSocket.
- The collection service sends event-driven messages directly to the ESB.
- The ESB writes processed data to an internal Aeron channel.
- Tickerplant bridges internal Aeron data to external Aeron channels.
- External Aeron channels feed `Client 1`, `Client 2`, `Client 3`.
- External Aeron channels also feed a Data Warehousing Service.
- The data warehouse writes processed data to Redshift.

Current code differs from the diagram:

- Redshift/data warehouse writer is removed.
- Durable storage is mixed: live raw websocket accepted rows are DB-primary
  through the async writer, and the canonical DB sink is wired in
  `DataProcessor` / cluster runtime.
- NDJSON/S3 remains for recording-capture, offline replay, debug, import, and
  export workflows, not the live source of truth.
- FeaturePlant, frontend adapter, and research export default to canonical DB
  rows. Recording remains an explicit legacy/debug/demo/import/export source.
- Current code adds raw replay, REST backfill, stream recorder, featureplant,
  frontend adapter, stream tap, Prometheus/Grafana, and profiling.

## Current Runtime Path

Main path:

```text
Kalshi REST/WebSocket
  -> KalshiSystem / KalshiWebSocketClient
  -> KalshiIngressEnvelope byte[] ingress
  -> ClientClusterOrchestrator.writeToCluster(byte[])
  -> Aeron Cluster / ESBClusteredService scratch byte[] parse
  -> DataProcessor.processMessage(byte[], offset, length)
  -> internal Aeron stream
  -> Tickerplant
  -> external Aeron streams
  -> stream-recorder / featureplant / frontend-adapter / streamtap
```

Important classes:

- `KalshiSystem`: live ingestion entrypoint.
- `KalshiWrapper`: Kalshi REST wrapper and request signing.
- `KalshiWebSocketClient`: custom WebSocket client, subscription ack handling,
  raw ingest byte envelope creation.
- `ClientClusterOrchestrator`: byte[] Aeron Cluster ingress writer.
- `ClusterMain`: Aeron Cluster node startup.
- `ESBClusteredService`: leader-side cluster message handling with reusable
  ingress scratch buffer and recovery snapshot payloads.
- `DataProcessor`: raw/canonical parsing, order book derived events, metrics,
  and DB offer counters.
- `SourceSequenceMonitor`: optional monotonic subscription-wide sequence
  watermark with snapshot/restore support.
- `OrderBookStateManager`: per-market snapshot/delta state and derived
  top-of-book recovery pauses with recovery checkpoints.
- `Tickerplant`: routes internal canonical JSON by `stream_name`.
- `AeronCanonicalEnvelopeSource`: live canonical consumer source for
  FeaturePlant-style modules; fair-polls stream subscriptions across calls.
- `StreamRegistry`: external stream IDs and stream contracts.

## Sequence And Derived State Semantics

Current:

- `SourceSequenceMonitor` keeps one monotonic watermark per subscription.
  Forward gaps emit `source_sequence_gap` and advance; duplicate or older
  events emit `non_monotonic_source_sequence` without rewinding.
- `OrderBookState` keeps a per-market watermark but does not require forward
  source sequence values to be contiguous per market. Forward skips can apply as
  subscription-wide interleaving; duplicate/backward per-market deltas pause
  before mutation.
- If a source-sequence anomaly hits an order book delta, `DataProcessor` still
  publishes raw, source gap, and canonical events, but skips derived order-book
  apply and marks that market paused until a fresh snapshot. When derived
  order-book mode is disabled, the anomaly path does not create book state.
- Crossed books suppress the invalid crossed `top_of_book_update`, emit
  `SequenceGapEvent(reason="crossed_book")`, and pause the market until a fresh
  snapshot.
- Generated sequence-gap events count producer-side
  `backend_orderbook_sequence_gap_total`; `crossed_book` sequence-gap events
  also count `backend_orderbook_crossed_total` from the sequence-gap labels.
  Distribution metrics remain sampled; counters remain exact.
- `DataProcessorRecoveryState` captures source sequence watermarks and order
  book recovery checkpoints. Cluster snapshots restore those watermarks and
  fail-closed paused book checkpoints; they do not restore price levels or full
  order book depth.

Planned:

- Automated fresh snapshot reload/live `get_snapshot` wiring, reconnect
  restore, and full-depth book restore remain roadmap work.

## Stream Contracts

External Aeron streams:

| Stream | ID | Status |
| --- | ---: | --- |
| `raw.kalshi.websocket` | 10 | current |
| `canonical.trade` | 11 | current |
| `canonical.orderbook.snapshot` | 12 | current |
| `canonical.orderbook.delta` | 13 | current |
| `canonical.ticker` | 14 | current |
| `canonical.open_interest` | 15 | current |
| `derived.top_of_book` | 16 | current |
| `canonical.market_lifecycle` | 17 | current |
| `system.parser_errors` | 18 | current |
| `system.sequence_gaps` | 19 | current |

Internal tickerplant bus:

- `StreamRegistry.INTERNAL_CANONICAL = 20`

## Storage And Database

Live raw websocket accepted rows are DB-primary via the async writer, and
canonical DB writes are wired in `DataProcessor` / cluster runtime. Recording
files remain capture/offline/debug/import/export artifacts, not the live source
of truth. The DB writer uses split bounded raw/canonical queues.

Current recording/export layouts:

```text
recordings/raw-ingest/source=kalshi.websocket/date=yyyy-mm-dd/hour=hh/minute=mm/events.ndjson
recordings/canonical/stream=<stream_name>/date=yyyy-mm-dd/hour=hh/events.ndjson
recordings/raw-rest/endpoint=<rest_endpoint_with_dots_replaced_by_underscores>/date=yyyy-mm-dd/hour=hh/responses.ndjson
```

`recordings/raw-rest` is written only by the explicit historical backfill
recording target. Default historical raw REST storage is the
`raw_rest_responses` DB table. Set
`HISTORICAL_BACKFILL_PARTITION_GRANULARITY=minute` to add `minute=mm`.

Current database status:

- Redshift hot-path writer is removed.
- Postgres/Timescale support includes live raw writes, canonical DB sink
  wiring, raw replay reader support, historical REST canonical backfill, and
  historical raw REST response rows in `raw_rest_responses`.
- DB writer observability includes raw/canonical queue/drop/write metrics and
  `processor_db_offers_total` for accepted, dropped-full, and disabled
  best-effort non-blocking offers.
- DB schema/runtime wiring exists for raw/canonical paths. A canonical DB cursor
  reader primitive exists for the current single-writer path and backs the
  default FeaturePlant, frontend adapter, and research export DB sources. Its
  cursor is global over `canonical_commit_seq`; replay rows are excluded unless
  a replay id or include-replay option is supplied.
- `market_metadata` schema, isolated JDBC store boundary, and historical REST
  market discovery upsert wiring exist; live/runtime writes are not wired.
- `feature_outputs` schema, isolated JDBC store boundary, deterministic mapper,
  and explicit FeaturePlant DB output mode exist. Stdout remains the default;
  feature output batching/async writes are not wired.
- No S3-to-Timescale loader is implemented in this repo.

Remaining database migration work:

- Keep S3/file recordings as recording-capture/offline/debug/import/export
  artifacts.
- Finish DB query/API migration for raw/canonical data already written to
  Timescale/Postgres, while keeping recording import/export boundaries explicit.
- Wire versioned feature storage behind FeaturePlant when it can stay off the
  live hot path.
- Add query APIs over feature/canonical storage.

## Caches And Buffers

Most "cache" code is in-process memory, not durable cache infrastructure.

| Component | Type | Durable | Purpose |
| --- | --- | --- | --- |
| `RawIngestRecorder.queue` | bounded queue | no | async raw write buffer |
| `BoundedAsyncDbWriter.rawQueue` | bounded queue | no | non-blocking raw DB write buffer |
| `BoundedAsyncDbWriter.canonicalQueue` | bounded queue | no | non-blocking canonical DB write buffer |
| `ESBClusteredService.sessionMessageScratch` | growable byte buffer | no | leader ingress copy reuse |
| `FrontendFeatureStore.byMarket` | per-market deque | no | recent feature data for chart/quotes |
| `FrontendFeatureStore.latestByMarket` | map | no | latest feature by market |
| `BoundedFeatureOutputBuffer` | deque | no | embedded feature output buffer |
| `FeaturePlantService.metricHandles` | map | no | cached module/stream metric handles |
| `StreamTapServer.recentEvents` | deque | no | recent stream inspection |
| `TickerplantStreamRecorder.recentEvents` | deque | no | recent recorder event summaries |
| WebSocket ack maps | futures/maps | no | subscribe/update ack tracking |

Restart loses all in-memory cache state.

## Module Status

Legend:

- `current`: implemented enough to run.
- `skeleton`: boundary exists, production capability incomplete.
- `planned`: roadmap only.
- `removed`: legacy path no longer current.

| Area | Status | Notes |
| --- | --- | --- |
| Live Kalshi ingestion | current | configurable market discovery/subscription exists |
| WebSocket ping/pong | current-basic | ping replies exist; reconnect/restore is not complete |
| WebSocket reconnect/subscription restore | planned | roadmap hardening |
| REST wrapper | current | simple wrapper; weak retry/backoff/error taxonomy |
| Aeron Cluster ESB | current | message path exists; recovery snapshots write/read processor recovery payloads and snapshot publication offer uses terminal fail-fast plus bounded retry |
| Cluster snapshot/restore | current-basic | restores source watermarks and paused order-book recovery checkpoints; no full order book depth restore; live fresh snapshot actuator/reconnect remains planned |
| Canonical event model | current | stream registry and serializer exist |
| Kalshi WS parser | current | parser error events exist |
| Kalshi REST parser | current | used by historical backfill |
| Order book state/top-of-book | current | forward interleaved subscription sequences can apply; duplicate/backward per-market deltas pause before mutation; crossed books suppress invalid crossed `top_of_book_update`, emit `SequenceGapEvent(reason="crossed_book")`, and pause until a fresh snapshot; recovery checkpoints restore paused fail-closed state without depth; automated fresh snapshot reload remains planned |
| Source sequence monitor | current-basic | optional monotonic subscription watermark; forward gaps advance, duplicate/older events do not rewind; watermarks can snapshot/restore |
| Tickerplant routing | current | JSON `stream_name` routing |
| Raw websocket recording | current | DB-primary accepted-row path; `raw-ingest` files for recording/debug/offline/export |
| Canonical stream recording | current | canonical DB sink wired in `DataProcessor`/cluster runtime; downstream Aeron recording remains capture/offline/debug/export |
| Raw REST backfill storage | current | DB-primary `raw_rest_responses`; `raw-rest` files are explicit recording/export/debug |
| S3 recording sync | current-basic | sidecar/script present |
| Object-store loader/query backfill | planned | no full loader/query path |
| Raw ingress replay | current-basic | Timescale reader default plus local NDJSON import/debug; publishes byte[] ingress envelopes with `replay_id` |
| Historical REST backfill | current-basic | canonical DB, raw REST DB, and market metadata DB targets are default when DB config is present; canonical/raw-rest NDJSON are explicit legacy/debug/export |
| FeaturePlant runtime | skeleton/current-basic | source + module dispatch exists; canonical DB source is default, live Aeron fair-polls streams and parses envelopes from byte[] while retaining payload strings, recording is explicit legacy/debug/demo/import; fair-poll coverage uses fake pollers |
| Feature modules | current-basic | BBO, ticker snapshot, trade tape |
| Versioned `feature.*` streams | planned | no feature stream registry/publisher |
| Persistent feature store | current-basic | `feature_outputs` schema/store plus explicit `FEATUREPLANT_OUTPUT=db` FeaturePlant sink exist; stdout remains default and async/batched feature writes are absent |
| MarketStateStore | planned | latest trade/ticker/OI/BBO/depth runtime store absent; `market_metadata` schema/store plus historical REST metadata upsert wiring exists |
| Bar/bucket modules | planned | frontend synthesizes bars from BBO midpoint |
| Feature/query API | current-basic | `/features` inspection endpoint serves buffered feature outputs; `/bars` and WS features absent |
| Frontend adapter | current-demo | HTTP polling datafeed demo; module-driven canonical DB source remains default, recording is explicit legacy/debug/demo; optional `feature_outputs` startup snapshot mode reads persisted feature rows |
| Replay viewer controls | planned | pause/resume/seek/speed absent |
| Research CSV export | current-basic | canonical DB source is default, recording is explicit legacy/export/debug/import |
| Semantic parser/schema | planned | absent |
| Ontology/chain builder | planned | absent |
| Constraint engine | planned | absent |
| Synthetic contract engine | planned | absent |
| Pricing model layer | planned | absent |
| Arb scanner | planned | absent |
| Metrics | current-basic | custom Prometheus text metrics; hot-path handles resolve storage once and unused resolved handles stay absent from `snapshot()` and Prometheus output until used |
| Grafana/Prometheus | current-basic | config exists |
| DataQualityEvent stream | planned | absent |
| OpenTelemetry tracing | planned | absent |
| Alerts/runbooks | planned-basic | docs exist; alert rules incomplete |
| Redshift writer | removed | shown in legacy diagram only |

## Docker Profiles

| Profile | Purpose | Status |
| --- | --- | --- |
| `single-node-local` | node0 only | current, not full ingestion stack |
| `cluster-live` | 3-node cluster + wsclient | current |
| `recording-capture` | single-node capture + recorder/S3 sync | current |
| `raw-replay` | raw replay CLI | current-basic |
| `historical-backfill` | REST backfill CLI | current-basic |
| `featureplant` | featureplant CLI | skeleton/current-basic |
| `frontend-integration` | frontend adapter HTTP service | current-demo |
| `observability` | Prometheus/Grafana/stream services | current-basic |

README caveat:

- `single-node-local` starts `node0`; it does not start `wsclient`.
- Raw replay defaults to Timescale unless `RAW_REPLAY_SOURCE=local-ndjson` is set.

## Frontend Status

Current:

- Static page under `frontend/tradingview-lightweight`.
- Uses TradingView Lightweight Charts from CDN.
- Uses Java `FrontendAdapterServer` endpoints:
  - `/datafeed/config`
  - `/datafeed/symbols`
  - `/datafeed/search`
  - `/datafeed/history`
  - `/datafeed/time`
  - `/symbols`
  - `/quotes`
  - `/health`
  - `/metrics`
- Quotes are HTTP polling, not streaming.
- Bars are synthesized from `feature.bbo` midpoint.
- Volume is sample count, not true trade volume.

Missing:

- production frontend app
- WebSocket/SSE live quote stream
- replay controls
- durable query backend
- mobile layout hardening
- frontend smoke/visual tests

## Testing And CI

Current:

- Java 17/Maven project.
- Maven Wrapper is present.
- 43 test files under `src/test/java`.
- GitHub Actions runs `mvn -B test` and uses Node 24-native
  `actions/checkout@v6` and `actions/setup-java@v5`.
- Disabled live network tests exist for Kalshi wrapper.
- EC2 deploy workflow exists.

Gaps:

- no CI `mvn package` gate
- no Docker build gate before deploy
- no `docker compose config` gate
- no container health smoke gate
- no dependency vulnerability gate
- no deploy rollback gate

## Security And Operations Risks

High priority:

- HTTP endpoints have no authentication.
- Some services bind to `0.0.0.0`.
- `/events` endpoints can expose payloads/internal data.
- Aeron/cluster UDP relies on network isolation.
- Raw recordings may contain sensitive source payloads.
- CLI `--db-password=` can expose DB password in process args.

Reliability risks:

- basic cluster recovery snapshot omits full order book depth
- no automated order book fresh snapshot reload
- weak REST retry/backoff behavior
- no full websocket reconnect/subscription restore
- raw recorder can drop on full queue by configuration
- tickerplant parses JSON per message for routing

Maintainability risks:

- Jackson, `org.json`, and `json-simple` are mixed.
- Current docs mix completed features and roadmap items.
- README contains stale or incomplete run instructions.
- One Maven artifact contains many runtime roles.

## Demo Readiness

Safe demo path should use DB-backed local/demo rows, not live credentials.
Recording mode is an explicit offline fixture path.

Recommended demo scope:

1. Show current architecture and distinguish roadmap.
2. Seed local Timescale/Postgres demo rows with `scripts/db-primary-demo-seed.sh`.
3. Optionally run `featureplant` from the DB default source for real market rows.
4. Run `frontend-adapter` from persisted `feature_outputs` startup snapshot mode.
5. Open `frontend/tradingview-lightweight/index.html` against the DB-backed adapter.
6. Show `/health` and `/metrics`.
7. Optionally show raw replay dry-run.

The canonical walkthrough is `docs/demo_db_primary_walkthrough.md`; the stable
local seed is `scripts/db-primary-demo-seed.sh`; the frontend HTTP smoke is
`scripts/db-primary-demo-smoke.sh`.

Do not demo as completed:

- pricing model
- arbitrage scanner
- semantic ontology
- durable feature store
- full database query platform
- production-grade reconnect/recovery

## Immediate Cleanup Plan

1. Add a status table to README: current / skeleton / planned / removed.
2. Keep stable DB-seeded demo rows aligned with frontend demo expectations.
3. Fix README run commands for `single-node-local` and raw replay.
4. Add Maven Wrapper.
5. Add CI gates: `mvn test`, `mvn package`, Docker build, compose config.
6. Add streamtap/recorder health smoke checks for non-demo profiles.
7. Default HTTP admin endpoints to localhost or add auth.
8. Define remaining DB migration: DB query API and recording import/export
   boundaries.
9. Decide whether to split Maven modules or at least enforce package boundaries.
10. Mark semantic/pricing/arb modules as future work only.
