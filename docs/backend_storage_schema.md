# Backend Storage Schema

## Local Journal

Local development and replay use newline-delimited JSON under `BACKEND_JOURNAL_ROOT`.

```text
journal/
  raw/
    yyyy-mm-dd.ndjson
  canonical/
    raw_kalshi_websocket/
    canonical_trade/
    canonical_orderbook_snapshot/
    canonical_orderbook_delta/
    canonical_ticker/
    canonical_open_interest/
    derived_top_of_book/
    canonical_market_lifecycle/
    system_parser_errors/
    system_sequence_gaps/
```

The writer is append-only and idempotent by `event_id`. On startup it scans existing `.ndjson` files and skips duplicate event ids.

## Warehouse Boundary

The hot path no longer writes directly to Redshift/Postgres. Database-backed storage is optional and configured through:

- `BACKEND_DATABASE_URL`
- `BACKEND_DATABASE_USER`
- `BACKEND_DATABASE_PASSWORD`

The recommended deployment pattern is:

1. Journal raw and canonical events locally or to durable object storage.
2. Batch-copy canonical partitions into analytical storage.
3. Rebuild canonical partitions from raw events when parser schemas change.

## Backfill

Use `edu.illinois.group8.replay.ReplayCli` against a raw journal root to regenerate canonical output. The replay service marks output with `metadata.replay_id` and preserves original event timestamps.
