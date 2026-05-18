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
4. Record raw and canonical data for replay, research, and demos.
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
- Durable storage is file/object based: `raw-ingest`, `canonical`, `raw-rest`.
- Timescale/Postgres is only an optional raw replay source today.
- Current code adds raw replay, REST backfill, stream recorder, featureplant,
  frontend adapter, stream tap, Prometheus/Grafana, and profiling.

## Current Runtime Path

Main path:

```text
Kalshi REST/WebSocket
  -> KalshiSystem / KalshiWebSocketClient
  -> ClientClusterOrchestrator
  -> Aeron Cluster / ESBClusteredService
  -> DataProcessor
  -> internal Aeron stream
  -> Tickerplant
  -> external Aeron streams
  -> stream-recorder / featureplant / frontend-adapter / streamtap
```

Important classes:

- `KalshiSystem`: live ingestion entrypoint.
- `KalshiWrapper`: Kalshi REST wrapper and request signing.
- `KalshiWebSocketClient`: custom WebSocket client, subscription ack handling,
  raw ingest envelope creation.
- `ClusterMain`: Aeron Cluster node startup.
- `ESBClusteredService`: leader-side cluster message handling.
- `DataProcessor`: raw/canonical parsing, order book derived events, metrics.
- `Tickerplant`: routes internal canonical JSON by `stream_name`.
- `StreamRegistry`: external stream IDs and stream contracts.

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

Current source of truth is recording files, not a database.

Current durable layouts:

```text
recordings/raw-ingest/source=kalshi.websocket/date=yyyy-mm-dd/hour=hh/minute=mm/events.ndjson
recordings/canonical/stream=<stream_name>/date=yyyy-mm-dd/hour=hh/minute=mm/events.ndjson
recordings/raw-rest/endpoint=<rest_endpoint>/date=yyyy-mm-dd/hour=hh/minute=mm/responses.ndjson
```

Current database status:

- Redshift/Postgres hot-path writers are removed.
- Timescale/Postgres support exists only as a raw replay reader.
- No complete DB migrations are present.
- No canonical query schema is present.
- No persistent feature store schema is present.
- No S3-to-Timescale loader is implemented in this repo.

Planned database direction:

- Treat S3/file recordings as immutable event lake.
- Load raw/canonical recordings into query stores such as TimescaleDB.
- Add versioned feature storage behind FeaturePlant.
- Add query APIs over feature/canonical storage.

## Caches And Buffers

Most "cache" code is in-process memory, not durable cache infrastructure.

| Component | Type | Durable | Purpose |
| --- | --- | --- | --- |
| `RawIngestRecorder.queue` | bounded queue | no | async raw write buffer |
| `FrontendFeatureStore.byMarket` | per-market deque | no | recent feature data for chart/quotes |
| `FrontendFeatureStore.latestByMarket` | map | no | latest feature by market |
| `BoundedFeatureOutputBuffer` | deque | no | embedded feature output buffer |
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
| Aeron Cluster ESB | current | message path exists |
| Cluster snapshot/restore | planned | current snapshot is effectively empty |
| Canonical event model | current | stream registry and serializer exist |
| Kalshi WS parser | current | parser error events exist |
| Kalshi REST parser | current | used by historical backfill |
| Order book state/top-of-book | current | no robust recovery after restart/gaps |
| Source sequence monitor | current-basic | optional; semantics caveated in docs |
| Tickerplant routing | current | JSON `stream_name` routing |
| Raw websocket recording | current | `raw-ingest` source-of-truth files |
| Canonical stream recording | current | downstream Aeron consumer recording |
| Raw REST recording | current | historical backfill audit trail |
| S3 recording sync | current-basic | sidecar/script present |
| Object-store loader/query backfill | planned | no full loader/query path |
| Raw ingress replay | current-basic | local NDJSON and Timescale reader |
| Historical REST backfill | current-basic | writes raw-rest and canonical |
| FeaturePlant runtime | skeleton | source + module dispatch exists |
| Feature modules | current-basic | BBO, ticker snapshot, trade tape |
| Versioned `feature.*` streams | planned | no feature stream registry/publisher |
| Persistent feature store | planned | no durable feature DB |
| MarketStateStore | planned | latest trade/ticker/OI/BBO/depth store absent |
| Bar/bucket modules | planned | frontend synthesizes bars from BBO midpoint |
| Feature/query API | planned | `/features`, `/bars`, WS features absent |
| Frontend adapter | current-demo | HTTP polling datafeed demo |
| Replay viewer controls | planned | pause/resume/seek/speed absent |
| Research CSV export | current-basic | recording source only |
| Semantic parser/schema | planned | absent |
| Ontology/chain builder | planned | absent |
| Constraint engine | planned | absent |
| Synthetic contract engine | planned | absent |
| Pricing model layer | planned | absent |
| Arb scanner | planned | absent |
| Metrics | current-basic | custom Prometheus text metrics |
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
- 19 test files under `src/test/java`.
- GitHub Actions runs `mvn -B test`.
- Disabled live network tests exist for Kalshi wrapper.
- EC2 deploy workflow exists.

Gaps:

- no Maven Wrapper
- no local `mvn` available in this environment during audit
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

- no cluster business-state restore
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

Safe demo path should use recorded data, not live credentials.

Recommended demo scope:

1. Show current architecture and distinguish roadmap.
2. Start from sample `recordings/canonical`.
3. Run `featureplant` over recording.
4. Run `frontend-adapter` against recording.
5. Open static chart demo.
6. Show `/health` and `/metrics`.
7. Optionally show raw replay dry-run.

Do not demo as completed:

- pricing model
- arbitrage scanner
- semantic ontology
- durable feature store
- full database query platform
- production-grade reconnect/recovery

## Immediate Cleanup Plan

1. Add a status table to README: current / skeleton / planned / removed.
2. Add a stable sample recording for video demo.
3. Fix README run commands for `single-node-local` and raw replay.
4. Add Maven Wrapper.
5. Add CI gates: `mvn test`, `mvn package`, Docker build, compose config.
6. Add health smoke checks for frontend adapter, streamtap, recorder.
7. Default HTTP admin endpoints to localhost or add auth.
8. Define DB roadmap: recording lake -> loader -> Timescale schema -> query API.
9. Decide whether to split Maven modules or at least enforce package boundaries.
10. Mark semantic/pricing/arb modules as future work only.
