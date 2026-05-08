# Stream Recorder Runbook

`stream-recorder` is a pluggable tickerplant consumer. It subscribes to normalized distributed streams and writes newline-delimited JSON under:

```text
/app/recordings/canonical/stream=<stream_name>/date=<utc-date>/hour=<hh>/events.ndjson
```

Set `STREAM_RECORDER_PARTITION_GRANULARITY=minute` to add a `minute=<mm>` partition between `hour=<hh>` and `events.ndjson`. That is the better setting for S3-backed query stores because active files become stable and uploadable within roughly one minute instead of waiting for an hour boundary.

Featureplant and query/export modules can use this directory as a historical
source. In live capture, it reflects what a real Aeron consumer observed. In
REST backfill, `HistoricalBackfillCli` writes parsed canonical events into the
same layout.

## Configuration

- `STREAM_RECORDER_AERON_CHANNEL`: tickerplant channel. Falls back to `AERON_EXTERNAL_CHANNEL`.
- `STREAM_RECORDER_STREAMS`: normalized stream contracts to persist, or
  `all-normalized` to subscribe to every normalized public stream.
- `STREAM_RECORDER_OUTPUT_ROOT`: recording root, default `/app/recordings`.
- `STREAM_RECORDER_PORT`: HTTP health and metrics port, default `8092`.
- `STREAM_RECORDER_TIMESTAMP_SOURCE`: `system_nano` for monotonic stage durations or `ptp_system_clock` for OS wall-clock nanoseconds when the host clock is PHC/PTP-disciplined.
- `STREAM_RECORDER_PARTITION_GRANULARITY`: `hour` or `minute`, default `hour`.
- `ENABLE_EC2_PTP`: reports host PTP discipline when the device is not mounted inside the container.
- `S3_RECORDING_BUCKET`: optional bucket for the S3 sync sidecar.
- `S3_RECORDING_PREFIX`: default `kalshi-normalized/`.
- `S3_RECORDING_SUBTREES`: comma-separated recording subtrees to upload. For
  the EC2 capture profile this should include `canonical,raw-ingest,raw-rest`.
- `S3_DELETE_AFTER_UPLOAD`: default `false`. Set to `true` for whole-universe
  captures on the EC2 root volume so confirmed uploads are evicted locally.

The stream recorder is intentionally downstream from the normalized tickerplant
streams. It measures and archives what any Aeron consumer sees. It is not the
authoritative raw replay source; use `raw-ingest` recordings for end-to-end
replay through cluster ingress.
- `S3_UPLOAD_INTERVAL_SECONDS`: default `60`.
- `S3_UPLOAD_MIN_AGE_SECONDS`: default `120`; avoids uploading actively written files.

When `S3_RECORDING_BUCKET` is set, `s3-recording-sync` uploads stable local recorder files as gzip objects:

```text
s3://<bucket>/<prefix>/canonical/stream=canonical.trade/date=2026-05-03/hour=10/events.ndjson.gz
s3://<bucket>/<prefix>/canonical/stream=canonical.trade/date=2026-05-03/hour=10/minute=15/events.ndjson.gz
```

S3 should be treated as the durable event lake. Query stores such as TimescaleDB should load from this bucket instead of subscribing directly to the tickerplant.

## Endpoints

```text
GET /health
GET /metrics
GET /events
```

The recorder annotates each stored event with `recorder_metadata`:

- `consumer_receive_ts_ns`
- `storage_enqueue_ts_ns`
- `storage_commit_start_ts_ns`
- `storage_commit_wall_ts`
- `storage_commit_ts_ns`

Recorded canonical storage is historical input for featureplant, visualization,
backtesting, and research export. Full end-to-end replay uses
`edu.illinois.group8.replay.raw.RawIngressReplayCli` against the raw
source-of-truth store.

## Verification

```bash
curl http://127.0.0.1:8092/health
curl http://127.0.0.1:8092/metrics
find recordings/canonical -type f | head
```

Expected critical metrics include `backend_storage_commit_total`, `backend_storage_commit_latency_ns_count`, `stream_recorder_events_total`, and data-quality counters such as `backend_orderbook_sequence_gap_total` when those events appear on the stream.
When websocket ingress envelopes are present and both ingress and recorder use
the same timestamp mode, `tickerplant_stream_recorder_e2e_latency_ns` measures
raw websocket receive to downstream Aeron-client receive latency.
