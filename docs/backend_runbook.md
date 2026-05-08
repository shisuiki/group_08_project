# Backend Runbook

## Replay

Raw replay reads the `raw-ingest` source-of-truth capture and injects the
original websocket payloads back into cluster ingress.

```bash
java -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.replay.raw.RawIngressReplayCli \
  --root=recordings/raw-ingest \
  --dry-run
```

Canonical storage replay is for tickerplant stream/load testing, not full
end-to-end parser replay:

```bash
java -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.replay.recording.StorageBackedRecordingReplayCli \
  --root=recordings \
  --mode=fixed-rate \
  --fixed-rate=50000 \
  --loop-count=10
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
monitoring needs a connection-aware ingestion envelope before it can be enabled
for sharded open-market capture without false positives.
`BACKEND_ORDERBOOK_DERIVED_ENABLED=false` disables derived top-of-book and book
state gap generation while preserving the normalized canonical recorder streams.
Use it for high-volume recorder soak tests when storage fidelity matters more
than live derived book analytics.

For EC2 recorder-only soak tests, use the `recording-capture` compose profile.
It starts a single Aeron cluster member, `wsclient-capture`, the stream recorder,
and the S3 sync sidecar without stream tap, Prometheus, Grafana, or downstream
feature/query modules.

The source-of-truth replay log is the raw websocket capture, not the downstream
stream recorder. Enable:

- `RAW_INGEST_RECORDER_ENABLED=true`
- `RAW_INGEST_RECORDER_ROOT=/app/recordings/raw-ingest`
- `RAW_INGEST_RECORDER_TIMESTAMP_SOURCE=ptp_system_clock`
- `BACKEND_LEGACY_JOURNAL_ENABLED=false` to stop writing the old local
  `/app/journal/raw` and `/app/journal/canonical` files during capture so the
  EC2 root volume is not consumed by duplicate storage.
- `BACKEND_RAW_RECORDING_ROOT=` unless the optional parser-side raw mirror is
  needed.
- `BACKEND_CANONICAL_RECORDING_ROOT=/app/recordings/producer-canonical`

This produces the key storage views:

- `raw-ingest`: exact inbound Kalshi websocket JSON as seen by `wsclient`, with
  receive PTP timestamps, before cluster injection.
- `producer-raw`: the parser's `RawSourceEvent` written by `FileEventJournal`
  when the tickerplant processes the raw payload. This is configurable but
  redundant when `raw-ingest` is enabled.
- `producer-canonical`: normalized canonical events written by
  `FileEventJournal` at production time.

The downstream `stream-recorder` remains useful for measuring what a real Aeron
consumer observes from the distributed tickerplant streams, but it is a strict
subset of injected raw traffic after normalization and should not be treated as
the authoritative replay database.

```bash
docker compose --env-file .env --profile cluster-live --profile observability stop \
  wsclient node0 node1 node2 streamtap prometheus grafana
docker compose --env-file .env --profile recording-capture up -d --build \
  node0-capture stream-recorder s3-recording-sync wsclient-capture
```

For S3-backed whole-universe capture, set:

- `S3_RECORDING_SUBTREES=canonical,raw-ingest,producer-canonical`
- `S3_DELETE_AFTER_UPLOAD=true`

Keep `KALSHI_MARKET_DISCOVERY_MAX_MARKETS=0` for the whole discovered open
market universe. Set it to a positive count only for bounded smoke tests.

Raw replay injects recorded websocket payloads back into Aeron cluster ingress:

```bash
docker compose --env-file .env --profile raw-replay run --rm raw-ingress-replay \
  --root=/app/recordings/raw-ingest --mode=original-timestamps --speed=10
```

Use `--dry-run` to validate file ordering without publishing into the cluster.

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

### S3 Recording Outage

Symptoms: `s3-recording-sync` upload failures or local disk growth.

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

Use container stop or process interruption. The raw-ingest and canonical
recorders write one event per line and close file handles per append, so
completed lines are durable without a separate flush command.
