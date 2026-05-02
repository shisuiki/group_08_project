# Backend Schema Gap Report

Status: implemented for the core backend milestone.

Sources checked:

- Kalshi WebSocket connection and channel docs: https://docs.kalshi.com/getting_started/quick_start_websockets
- Orderbook updates: https://docs.kalshi.com/websockets/orderbook-updates
- Market ticker: https://docs.kalshi.com/websockets/market-ticker
- Public trades: https://docs.kalshi.com/websockets/public-trades
- Market lifecycle: https://docs.kalshi.com/websockets/market-%26-event-lifecycle
- Fixed-point migration: https://docs.kalshi.com/getting_started/fixed_point_migration
- REST trades/markets/orderbook/historical docs: https://docs.kalshi.com/api-reference/market/get-trades, https://docs.kalshi.com/api-reference/market/get-markets, https://docs.kalshi.com/api-reference/market/get-market-orderbook, https://docs.kalshi.com/getting_started/historical_data

## Existing Field Usage

The old live parser used these fields:

- Top-level: `type`, `sid`, `seq`
- Trade: `market_ticker`, `yes_price`, `no_price`, `count`, `taker_side`, `ts`
- Ticker: `market_ticker`, `price`, `yes_bid`, `yes_ask`, `volume`, `open_interest`, `dollar_volume`, `dollar_open_interest`, `ts`
- Orderbook snapshot: `market_ticker`, `yes`, `no`
- Orderbook delta: `market_ticker`, `price`, `delta`, `side`

## Current Kalshi Mapping

| Source message | Current field | Canonical field | Notes |
| --- | --- | --- | --- |
| all websocket | `type` | `source_channel` | Used for source audit and parser routing. |
| all websocket | `sid` | `source_subscription_id` | Nullable for messages without subscription id. |
| sequenced websocket | `seq` | `source_sequence` | Used for order book gap detection. |
| all market messages | `msg.market_ticker` | `market_ticker` | REST historical trades use `ticker`; parser accepts both. |
| all market messages | `msg.market_id` | `market_id` | Nullable because older/REST shapes may omit it. |
| trade | `trade_id` | `trade_id` | Nullable for old fixtures. |
| trade | `yes_price_dollars`, `no_price_dollars` | `yes_price_micros`, `no_price_micros` | Old `yes_price`/`no_price` cents accepted as fallback. |
| trade | `count_fp` | `quantity_micros` | Old integer `count` accepted as fallback. |
| trade | `taker_side` | `taker_side` | Preserved as source side. |
| trade | `ts_ms` | `event_ts_ms` | Falls back to `ts * 1000`. |
| ticker | `price_dollars`, `yes_bid_dollars`, `yes_ask_dollars` | price fields in micros | Old cent fields accepted as fallback. |
| ticker | `volume_fp`, `open_interest_fp`, size `_fp` fields | quantity fields in micros | Old integer volume/open interest accepted as fallback. |
| orderbook snapshot | `yes_dollars_fp`, `no_dollars_fp` | `yes_bids`, `no_bids` price levels | Old `yes`/`no` cent arrays accepted as fallback. |
| orderbook delta | `price_dollars`, `delta_fp`, `side` | `price_micros`, `delta_quantity_micros`, `side` | Old `price`/`delta` accepted as fallback. |
| lifecycle | `event_type`, `open_ts`, `close_ts`, `fractional_trading_enabled`, `price_level_structure`, `additional_metadata` | `MarketLifecycleUpdate` fields | Event lifecycle fields are nullable. |

## Obsolete Or Renamed Fields

- `yes_price`, `no_price`, `price`, `yes_bid`, and `yes_ask` are legacy cent fields. Canonical output uses scaled integer dollar probability prices.
- `count`, `volume`, and `open_interest` are legacy integer quantity fields. Canonical output uses `*_micros`.
- `yes` and `no` orderbook arrays are legacy. Current Kalshi uses `yes_dollars_fp` and `no_dollars_fp`.
- One-letter internal message types (`T`, `K`, `S`, `D`, `R`, `O`) are no longer production stream contracts.

## Channel Notes

- WebSocket connections require API-key headers during handshake.
- `orderbook_delta` requires market filters and sends a snapshot before deltas.
- `ticker`, `trade`, and `market_lifecycle_v2` do not add extra channel-level authorization beyond the authenticated connection.
- Kalshi sends WebSocket ping frames with `heartbeat`; the client now responds with pong frames.
- Rate limits are token based. Standard authenticated requests cost tokens, and clients should back off on 429 responses.

## Migration Checklist

- Use `BackendConfig` environment fields instead of hardcoded URLs, key paths, DB settings, cluster addresses, and market filters.
- Use `KalshiCanonicalParser` for all live WebSocket payloads.
- Persist `RawSourceEvent` before parsing-derived canonical events.
- Publish only stream names documented in `docs/backend_stream_contracts.md`.
- Use `OrderBookStateManager` for snapshots/deltas and top-of-book generation.
- Use replay from the raw journal for deterministic backfills and parser compatibility checks.
