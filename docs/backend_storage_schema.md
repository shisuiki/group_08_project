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

## Producer Canonical

`producer-canonical` is written by `FileEventJournal` at production time after
normalization.

```text
recordings/producer-canonical/
  stream=<stream_name>/
    date=yyyy-mm-dd/
      hour=hh/
        minute=mm/
          events.ndjson
```

This is the preferred historical source for frontend APIs, backtests, and
canonical research exports.

## Downstream Canonical

`stream-recorder` can also persist what an Aeron client observes from the
tickerplant:

```text
recordings/canonical/
  stream=<stream_name>/
    date=yyyy-mm-dd/
      hour=hh/
        minute=mm/
          events.ndjson
```

Use this view for consumer latency and stream-fidelity checks. It is not the
authoritative replay source because it is downstream of normalization.

## Replay

Use `edu.illinois.group8.replay.raw.RawIngressReplayCli` to replay raw
websocket payloads through cluster ingress. Use
`edu.illinois.group8.replay.recording.StorageBackedRecordingReplayCli` only for
canonical stream/load testing.
