# Backend Storage Schema

The durable storage path is file/object based. The hot path no longer writes to
Redshift/Postgres and the old database writer packages have been removed.

## Raw Ingest

`raw-ingest` is the source of truth for full replay. It is written by the
websocket client before parsing or cluster injection.

```text
recordings/raw-ingest/
  source=kalshi.websocket/
    date=yyyy-mm-dd/
      hour=hh/
        minute=mm/
          events.ndjson
```

Each line contains the exact inbound websocket payload plus receive timestamps,
connection id, sequence, capture id, and payload hash.

When raw recordings are loaded into TimescaleDB for replay, `RawIngressReplayCli`
expects a row shape equivalent to:

- `raw_payload`
- `receive_ts_ns`
- `connection_id`
- `sequence`
- `raw_event_id`
- `market_ticker`

The table and column names are configurable through `RAW_REPLAY_TABLE` and
`RAW_REPLAY_*_COLUMN` settings, but replay selection should stay anchored to
receive timestamp, market ticker, or raw event id.

## Downstream Canonical

`canonical` is written by `TickerplantStreamRecorder`, which subscribes to the
tickerplant exactly as a downstream Aeron client would. This is the normalized
storage view used for consumer-latency measurement, stream-fidelity checks,
featureplant replay, visualization, backtests, and research exports.

```text
recordings/canonical/
  stream=<stream_name>/
    date=yyyy-mm-dd/
      hour=hh/
        minute=mm/
          events.ndjson
```

Each line contains the canonical event plus `recorder_metadata`, including the
downstream consumer receive timestamp. When live websocket ingestion uses the
ingress envelope, canonical event metadata also contains the raw websocket
receive timestamp as `metadata.ingest_ts_ns`, allowing e2e latency measurement
from websocket receipt to Aeron-client receipt.

## Raw REST Backfill

`raw-rest` is written by `HistoricalBackfillCli` when REST historical backfill
is run. It keeps the raw REST response alongside the canonical events parsed
from it, so backfill remains auditable.

```text
recordings/raw-rest/
  endpoint=<rest_endpoint>/
    date=yyyy-mm-dd/
      hour=hh/
        minute=mm/
          responses.ndjson
```

`HistoricalBackfillCli` writes parsed REST canonical events into
`recordings/canonical` by default, using the same stream/date/hour/minute
layout as the downstream stream recorder.

## Replay

Use `edu.illinois.group8.replay.raw.RawIngressReplayCli` to replay raw
websocket payloads through cluster ingress. Production replay selects exact raw
events from the canonical raw source-of-truth store, usually TimescaleDB loaded
from S3-backed raw recordings. The local NDJSON adapter remains available only
for development fixtures with `RAW_REPLAY_SOURCE=local-ndjson`.
