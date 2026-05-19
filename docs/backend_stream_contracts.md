# Backend Stream Contracts

Serialization: JSON with snake_case fields.

All events include:

- `event_id`
- `event_type`
- `schema_version`
- `stream_name`
- `metadata.source`
- `metadata.source_channel`
- `metadata.source_subscription_id`
- `metadata.source_sequence`
- `metadata.market_ticker`
- `metadata.market_id`
- `metadata.event_ts_ms`
- `metadata.ingest_ts_ns`
- `metadata.publish_ts_ns`
- `metadata.raw_event_id`
- `metadata.replay_id`

| Stream | ID | Schema | Ordering | Replay | Retention |
| --- | ---: | ---: | --- | --- | --- |
| `raw.kalshi.websocket` | 10 | 1 | Ingest order | Yes | DB raw ingest; NDJSON/S3 explicit capture/export |
| `canonical.trade` | 11 | 1 | Source order where available | Yes | DB canonical; NDJSON/S3 explicit capture/export |
| `canonical.orderbook.snapshot` | 12 | 1 | Source subscription sequence | Yes | DB canonical; NDJSON/S3 explicit capture/export |
| `canonical.orderbook.delta` | 13 | 1 | Source subscription sequence | Yes | DB canonical; NDJSON/S3 explicit capture/export |
| `canonical.ticker` | 14 | 1 | Ingest/source order | Yes | DB canonical; NDJSON/S3 explicit capture/export |
| `canonical.open_interest` | 15 | 1 | Ingest/source order | Yes | DB canonical; NDJSON/S3 explicit capture/export |
| `derived.top_of_book` | 16 | 1 | Derived from orderbook sequence | Yes | DB canonical; NDJSON/S3 explicit capture/export |
| `canonical.market_lifecycle` | 17 | 1 | Ingest/source order | Yes | DB canonical; NDJSON/S3 explicit capture/export |
| `system.parser_errors` | 18 | 1 | Ingest order | Yes | DB canonical; NDJSON/S3 explicit capture/export |
| `system.sequence_gaps` | 19 | 1 | Source subscription sequence | Yes | DB canonical; NDJSON/S3 explicit capture/export |

DB offers are best-effort and non-blocking. Drops or disabled DB writes are
observable through `db_*` writer metrics and `processor_db_offers_total`; they
do not block Aeron publication.

The internal tickerplant bus uses stream ID 20 and routes canonical JSON by `stream_name`. No production route depends on a fixed JSON character offset.

The deployed `cluster-live` profile also starts `streamtap`, a local HTTP endpoint bound to
`127.0.0.1:8080` on the host. It subscribes to the Aeron external channel and exposes:

- `GET /health` for stream counts and tap status.
- `GET /events` for recently observed stream payloads.
- `GET /metrics` for plain-text counters.

## Numeric Representation

- Prices use integer `*_price_micros` or `*_micros` fields scaled by 1,000,000 where `1_000_000` equals $1.00 probability.
- Contract counts use integer `*_quantity_micros` or `*_micros` fields scaled by 1,000,000 contracts.
- Original source decimal strings are retained on raw events and, for orderbook price levels, in `source_price` and `source_quantity`.
