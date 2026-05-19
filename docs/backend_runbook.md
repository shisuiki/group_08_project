# Backend Runbook

## Replay

Raw replay selects exact raw websocket payloads from DB/Timescale raw ingest
and injects them back into cluster ingress. S3-backed NDJSON remains a legacy
archive/import/debug source, not the default production replay store.

```bash
java -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.replay.raw.RawIngressReplayCli \
  --source=timescale \
  --db-url=jdbc:postgresql://storage-node:5432/kalshi \
  --start-receive-ts-ns=1780000000000000000 \
  --end-receive-ts-ns=1780000060000000000 \
  --dry-run
```

For local fixtures only, use the explicit local adapter:

```bash
java -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.replay.raw.RawIngressReplayCli \
  --source=local-ndjson \
  --local-root=recordings/raw-ingest \
  --max-events=100 \
  --dry-run
```

## Live Startup

Required live settings:

- `KALSHI_KEY_ID`
- `KALSHI_KEY_PATH`
- `KALSHI_MARKET_TICKERS`, `KALSHI_MARKET_SERIES_TICKER`, or `KALSHI_MARKET_SELECTION_MODE=open_markets`
- `CLUSTER_ADDRESSES`
- `IP` for the websocket client

Docker live mode:

```bash
cp .env.example .env
# fill KALSHI_KEY_ID, KALSHI_KEY_HOST_PATH, and market selection
docker compose --profile cluster-live up --build
```

`cluster-live` starts the live cluster, websocket client, and stream tap. It
does not start the NDJSON stream recorder or S3 sync sidecar, and the live
`wsclient` does not mount the raw file recorder path. DB raw ingest is the live
raw storage path.

For whole-open-market capture, set `KALSHI_MARKET_SELECTION_MODE=open_markets`.
The client pages through Kalshi `GET /markets?status=open`, subscribes `ticker`,
`trade`, and `market_lifecycle_v2` without market filters, and chunks
`orderbook_delta` subscriptions across the discovered open market tickers using
`KALSHI_ORDERBOOK_SUBSCRIPTION_CHUNK_SIZE`. It starts one `orderbook_delta`
subscription per shard, then uses `update_subscription add_markets` for the rest
of that shard so each chunk is acknowledged before it is counted as subscribed.
For large universes, it opens additional orderbook websocket shards after
`KALSHI_ORDERBOOK_MARKETS_PER_CONNECTION` filtered markets. Use
`KALSHI_MARKET_MVE_FILTER=exclude` when recording the regular binary orderbook
universe; Kalshi exposes MVE/combo markets through separate lifecycle semantics.
`KALSHI_WS_ACK_TIMEOUT_MS` controls how long startup waits for each Kalshi
subscribe/update acknowledgement.
`BACKEND_SOURCE_SEQUENCE_MONITOR_ENABLED` is off by default. Kalshi `sid`
values are scoped to an individual websocket connection, so source-sequence
monitoring should only be enabled for feeds where per-connection sequence
semantics are understood.
`BACKEND_ORDERBOOK_DERIVED_ENABLED=false` disables derived top-of-book and book
state gap generation while preserving the normalized canonical public streams.
Use it for high-volume recorder soak tests when storage fidelity matters more
than live derived book analytics.

For EC2 recorder-only soak tests or export soaks, use the explicit
`recording-capture` compose profile. It starts a single Aeron cluster member,
`wsclient-capture`, the raw file recorder wiring, the stream recorder, and the
S3 sync sidecar without stream tap, Prometheus, Grafana, or downstream
feature/query modules.
The raw file recorder only attaches to the websocket client when
`BACKEND_PROFILE=recording-capture`; live `wsclient` should not set
`RAW_INGEST_RECORDER_*`.

For `recording-capture`, enable:

- `RAW_INGEST_RECORDER_ENABLED=true`
- `RAW_INGEST_RECORDER_ROOT=/app/recordings/raw-ingest`
- `RAW_INGEST_RECORDER_TIMESTAMP_SOURCE=ptp_system_clock`
- `STREAM_RECORDER_STREAMS=all-normalized`
- `STREAM_RECORDER_TIMESTAMP_SOURCE=ptp_system_clock`

This produces the key NDJSON capture views:

- `raw-ingest`: exact inbound Kalshi websocket JSON as seen by `wsclient`, with
  receive PTP timestamps. The websocket receive timestamp is also carried
  through the ingress envelope into canonical event metadata as
  `metadata.ingest_ts_ns`; live handling keeps cluster publication first, then
  raw side-channel recording.
- `canonical`: normalized stream records written by `TickerplantStreamRecorder`
  after it consumes the external Aeron stream like any other tickerplant client.
  Use this for e2e latency and stream-fidelity measurement.
- `raw-rest`: exact REST responses recorded by historical backfill runs.

The downstream `stream-recorder` is the correct place to measure
recording-capture end-to-end latency from websocket receipt to Aeron-client
receipt. DB raw ingest remains the authoritative full-path replay source for
new live captures because replay exercises parser and cluster ingress again.

```bash
docker compose --env-file .env --profile cluster-live --profile observability stop \
  wsclient node0 node1 node2 streamtap prometheus grafana
docker compose --env-file .env --profile recording-capture up -d --build \
  node0-capture stream-recorder s3-recording-sync wsclient-capture
```

For `recording-capture` S3-backed whole-universe archive/export, set:

- `S3_RECORDING_SUBTREES=canonical,raw-ingest,raw-rest`
- `S3_DELETE_AFTER_UPLOAD=true`

Keep `KALSHI_MARKET_DISCOVERY_MAX_MARKETS=0` for the whole discovered open
market universe. Set it to a positive count only for bounded smoke tests.

Raw replay injects selected websocket payloads back into Aeron cluster ingress.
Provide a bounded selection by time, market ticker, raw event id, or max-event
limit:

```bash
docker compose --env-file .env --profile raw-replay run --rm raw-ingress-replay \
  --source=timescale \
  --start-receive-ts-ns=1780000000000000000 \
  --end-receive-ts-ns=1780000060000000000 \
  --mode=original-timestamps \
  --speed=10
```

Use `--dry-run` to validate selection/order without publishing into the cluster.

Historical REST backfill writes parsed canonical events to DB by default:

```bash
docker compose --env-file .env --profile historical-backfill run --rm historical-backfill
```

Set `HISTORICAL_BACKFILL_CANONICAL_TARGET=recording` only for explicit
legacy/debug/export canonical NDJSON. Set
`HISTORICAL_BACKFILL_RAW_REST_ENABLED=true` only when raw REST
`responses.ndjson` should be archived under `recordings/raw-rest`.

Set `HISTORICAL_BACKFILL_TICKERS` for explicit contracts, or let the service
discover markets from `GET /markets` using `HISTORICAL_BACKFILL_MARKET_STATUS`,
`HISTORICAL_BACKFILL_MVE_FILTER`, `HISTORICAL_BACKFILL_MAX_PAGES`, and
`HISTORICAL_BACKFILL_MAX_TICKERS`.

## Failure Modes

### WebSocket Auth Failure

Symptoms: websocket closes immediately or Kalshi sends an `error` message.

Actions:

- Verify `KALSHI_KEY_ID` and `KALSHI_KEY_PATH`.
- Confirm the private key file is mounted read-only at `KALSHI_KEY_PATH`.
- Check clock skew because signatures use millisecond timestamps.

### Sequence Gap

Symptoms: `system.sequence_gaps` events with reasons such as
`delta_before_snapshot`, `crossed_book`, or, when explicitly enabled for a
single-connection feed, `source_sequence_gap`.

Actions:

- Pause consumers for the affected `market_ticker`.
- Request a fresh orderbook snapshot through `update_subscription` with `get_snapshot` when wired to the live client.
- Resume the market after a new `orderbook_snapshot` is accepted.

### Parser Failure Spike

Symptoms: `system.parser_errors` rate increases.

Actions:

- Inspect the `raw-ingest` object referenced by `metadata.raw_event_id`.
- Compare the raw payload against `docs/backend_schema_gap_report.md`.
- Add or update a parser fixture before changing parser behavior.

### DB Writer Backlog Or Drop

Symptoms: DB writer queue metrics show sustained backlog or dropped raw input.

Actions:

- Keep cluster ingress running while sizing queue capacity and DB throughput.
- Check `DB_WRITER_*` settings and database connectivity.
- Use bounded raw replay from DB once the writer is healthy.

### Recording-Capture S3 Outage

Symptoms: `s3-recording-sync` upload failures or local disk growth in the
`recording-capture` profile.

Actions:

- Keep live ingestion running while local disk has headroom.
- Restore IAM/bucket/region connectivity.
- Re-run the S3 sync sidecar; stable local partitions are idempotently uploaded.

### Cluster Leader Change

Symptoms: cluster logs report role changes.

Actions:

- Data processing only handles session messages on the leader.
- Confirm follower nodes are healthy and Aeron directories are writable.
- Consumers should reconnect to public stream subscriptions if their local subscription stalls.

## Graceful Shutdown

Use container stop or process interruption. DB committed rows are the primary
durability boundary for live storage. In `recording-capture` or local NDJSON
debug runs, the raw-ingest and canonical recorders write one event per line and
close file handles per append, so completed lines are durable without a
separate flush command.
