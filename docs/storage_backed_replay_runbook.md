# Storage-Backed Recording Replay

`StorageBackedRecordingReplayCli` replays canonical events from the stream recorder output:

```text
recordings/canonical/**/*.ndjson
```

Both legacy daily files and partitioned recorder files such as
`recordings/canonical/stream=canonical.trade/date=2026-05-03/hour=10/events.ndjson`
are supported.

It is intended for two uses:

- Replay recorded tickerplant streams without Kalshi credentials.
- Load-test downstream consumers by redistributing stored events to many public stream contracts.

## Local Dry Run

Dry run reads storage, orders events across streams, and records replay metrics without publishing to Aeron:

```bash
java -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.replay.recording.StorageBackedRecordingReplayCli \
  --root=recordings \
  --streams=canonical.trade,canonical.ticker,canonical.open_interest,canonical.orderbook.snapshot,canonical.orderbook.delta,derived.top_of_book,system.sequence_gaps \
  --loop-count=5 \
  --dry-run
```

## Publish To Tickerplant Stream Consumers

```bash
java -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.replay.recording.StorageBackedRecordingReplayCli \
  --root=recordings \
  --channel='aeron:udp?endpoint=224.0.1.1:40456' \
  --mode=fixed-rate \
  --fixed-rate=50000 \
  --loop-count=10 \
  --replay-id=sustained-load-001
```

`--fixed-rate` is total events per second across all selected streams. Use `--loop-count` to create sustained load from a small recording.

## Pacing Modes

- `as-fast-as-possible`: publishes without intentional sleeps.
- `original-timestamps`: uses event timestamp deltas from storage, scaled by `--speed`.
- `fixed-rate`: publishes at an aggregate rate using `--fixed-rate`.

## Replay Metadata

By default the original recorded payload is preserved exactly. Add `--annotate-replay` to attach:

- `replay_metadata.replay_id`
- `replay_metadata.replay_loop`
- `replay_metadata.source_file`
- `replay_metadata.source_line`
- `replay_metadata.replay_publish_ts_ns`
- `replay_metadata.replay_source`

Annotation is useful for correctness/debug runs. Preserve payloads for load tests when minimizing replay overhead matters.

## Metrics

The CLI prints Prometheus text for:

- `backend_replay_sessions_active`
- `backend_replay_events_attempted_total`
- `backend_replay_events_published_total`
- `backend_replay_publish_failed_total`
- `backend_replay_lag_ms`
- `backend_publication_offer_total`
- `backend_publication_offer_failed_total`
- `backend_publication_latency_ns`
- `backend_publication_backpressure_ns`

## Docker Compose

```bash
docker compose --profile storage-replay up --build recording-replay
```

Relevant environment variables:

- `RECORDING_REPLAY_ROOT`, default `/app/recordings`
- `RECORDING_REPLAY_STREAMS`
- `RECORDING_REPLAY_MODE`
- `RECORDING_REPLAY_FIXED_RATE_PER_SECOND`
- `RECORDING_REPLAY_SPEED_MULTIPLIER`
- `RECORDING_REPLAY_LOOP_COUNT`
- `RECORDING_REPLAY_MAX_EVENTS`
- `RECORDING_REPLAY_ANNOTATE`
- `RECORDING_REPLAY_DRY_RUN`
