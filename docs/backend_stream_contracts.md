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
| `raw.kalshi.websocket` | 10 | 1 | Ingest order | Yes | Append-only local raw journal |
| `canonical.trade` | 11 | 1 | Source order where available | Yes | Local canonical journal; optional warehouse |
| `canonical.orderbook.snapshot` | 12 | 1 | Source subscription sequence | Yes | Local canonical journal |
| `canonical.orderbook.delta` | 13 | 1 | Source subscription sequence | Yes | Local canonical journal |
| `canonical.ticker` | 14 | 1 | Ingest/source order | Yes | Local canonical journal |
| `canonical.open_interest` | 15 | 1 | Ingest/source order | Yes | Local canonical journal |
| `derived.top_of_book` | 16 | 1 | Derived from orderbook sequence | Yes | Local canonical journal |
| `canonical.market_lifecycle` | 17 | 1 | Ingest/source order | Yes | Local canonical journal |
| `system.parser_errors` | 18 | 1 | Ingest order | Yes | Local canonical journal |
| `system.sequence_gaps` | 19 | 1 | Source subscription sequence | Yes | Local canonical journal |

The internal tickerplant bus uses stream ID 20 and routes canonical JSON by `stream_name`. No production route depends on a fixed JSON character offset.

## Numeric Representation

- Prices use integer `*_price_micros` or `*_micros` fields scaled by 1,000,000 where `1_000_000` equals $1.00 probability.
- Contract counts use integer `*_quantity_micros` or `*_micros` fields scaled by 1,000,000 contracts.
- Original source decimal strings are retained on raw events and, for orderbook price levels, in `source_price` and `source_quantity`.
