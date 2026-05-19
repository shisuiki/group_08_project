# Backend Storage Schema

New live storage is DB-primary. The async DB writer stores accepted raw
websocket input in `raw_ws_events`, and the canonical DB sink stores normalized
events in `canonical_events` from `DataProcessor` / cluster runtime. FeaturePlant
and the frontend adapter default to the canonical DB reader. File layouts under
`recordings/` remain for `recording-capture`, legacy archive/import, local
fixtures, demos, and debug exports.

The DB writer auto-enables when `DB_WRITER_DATABASE_URL` is present and
`DB_WRITER_ENABLED` is blank or unset. Set `DB_WRITER_ENABLED=false` for an
explicit opt-out; writes remain async, non-blocking, and drop-visible through
writer metrics.

## Raw Ingest

For new live ingestion, `raw_ws_events` is the primary replay/audit table. It
stores the exact websocket payload string with receive timestamps, connection
id, connection sequence, source, capture id, payload hash, and ingest status.

The legacy/capture NDJSON layout is:

```text
recordings/raw-ingest/
  source=kalshi.websocket/
    date=yyyy-mm-dd/
      hour=hh/
        minute=mm/
          events.ndjson
```

Each line contains the exact inbound websocket payload plus receive timestamps,
connection id, sequence, capture id, and payload hash. This file layout is not
the `cluster-live` source of truth.

When raw inputs are selected from TimescaleDB for replay,
`RawIngressReplayCli` expects a row shape equivalent to:

- `raw_payload`
- `receive_ts_ns`
- `connection_id`
- `connection_sequence`
- `raw_event_id`
- `market_ticker`

The table and column names are configurable through `RAW_REPLAY_TABLE` and
`RAW_REPLAY_*_COLUMN` settings, but replay selection should stay anchored to
receive timestamp, market ticker, or raw event id.

## Downstream Canonical

`canonical_events` is the normalized DB table for readers that need durable
canonical market data. `DataProcessor` enqueues canonical copies on the live
cluster path, and FeaturePlant reads this table by default through the canonical
DB cursor reader.

`recordings/canonical` is written by `TickerplantStreamRecorder`, which
subscribes to the tickerplant exactly as a downstream Aeron client would. This
NDJSON view is used for recording-capture archives, consumer-latency
measurement, stream-fidelity checks, import/debug/demo workflows, and retained
legacy research exports.

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
websocket payloads through cluster ingress. Production replay defaults to exact
raw events from DB/Timescale raw ingest. S3-backed NDJSON is an archive/import
source, and the local NDJSON adapter remains available only for fixtures,
imports, and debug with `RAW_REPLAY_SOURCE=local-ndjson`.
