# Parser Compatibility Report

The parser accepts current Kalshi WebSocket schemas and legacy project fixtures during migration.

Supported current live messages:

- `trade`
- `ticker`
- `orderbook_snapshot`
- `orderbook_delta`
- `market_lifecycle_v2`
- `event_lifecycle`
- `error`

Supported REST responses:

- `GET /markets/trades`
- `GET /markets`
- `GET /markets/{ticker}/orderbook`
- `GET /series/{series_ticker}/markets/{ticker}/candlesticks`

Compatibility fallbacks:

- Price fields accept current dollar strings and legacy integer cents.
- Quantity fields accept current fixed-point strings and legacy integer counts.
- Market ticker accepts `market_ticker` and REST historical `ticker`.
- Event timestamp accepts `ts_ms`, `ts` seconds, or ISO timestamp text when available.

Malformed or unsupported messages produce `ParserErrorEvent` and do not crash ingestion.
