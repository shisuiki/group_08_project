# Frontend Adapter Runbook

`FrontendAdapterMain` is a long-running HTTP service that converts a featureplant
stream into the TradingView Lightweight Charts UDF datafeed protocol. It hosts
a `FeaturePlantService` internally and consumes only `FeatureOutput` values via
`FeatureOutputSink`; it does not subscribe to Aeron streams directly and does
not parse canonical envelopes. See `docs/featureplant_runbook.md` for upstream
feature semantics.

The default source is canonical DB rows through `DbCanonicalEnvelopeSource`.
Replay rows are excluded by default. Recording input remains an explicit
legacy/demo/debug path for local fixture trees under `recordings/canonical`.

The adapter maintains one in-memory store keyed by `marketTicker`. Bars (OHLC)
are synthesized on read from buffered `feature.bbo` midpoints. When Plan 02
introduces a `bars` feature module, `/datafeed/history` will switch its source
to that feature stream without breaking the external API.

## Configuration

| Env var | Default | Purpose |
| --- | --- | --- |
| `FRONTEND_ADAPTER_HOST` | `127.0.0.1` | HTTP bind host |
| `FRONTEND_ADAPTER_PORT` | `8090` | HTTP port |
| `FRONTEND_ADAPTER_SOURCE` | `db` | `db`, `recording`, or `aeron` |
| `FRONTEND_ADAPTER_DB_URL` | `DB_WRITER_DATABASE_URL` | canonical DB URL when `source=db` |
| `FRONTEND_ADAPTER_DB_USER` | `DB_WRITER_DATABASE_USER` | canonical DB user |
| `FRONTEND_ADAPTER_DB_PASSWORD` | `DB_WRITER_DATABASE_PASSWORD` | canonical DB password |
| `FRONTEND_ADAPTER_DB_INCLUDE_REPLAY` | `false` | include replay rows when reading DB source |
| `FRONTEND_ADAPTER_DB_REPLAY_ID` | blank | restrict DB source to one replay id |
| `FRONTEND_ADAPTER_AERON_CHANNEL` | `AERON_EXTERNAL_CHANNEL` | tickerplant channel when `source=aeron` |
| `FRONTEND_ADAPTER_RECORDING_ROOT` | `$BASE_DIR/recordings` | recordings root when `source=recording` |
| `FRONTEND_ADAPTER_STREAMS` | `canonical.trade,canonical.ticker,derived.top_of_book` | canonical streams to subscribe |
| `FRONTEND_ADAPTER_MODULES` | `bbo,ticker_snapshot,trade_tape` | feature modules to enable |
| `FRONTEND_ADAPTER_MAX_FEATURES_PER_MARKET` | `10000` | ring-buffer cap per market |
| `FRONTEND_ADAPTER_MAX_SYMBOLS_INDEXED` | `5000` | cap on number of markets retained |
| `FRONTEND_ADAPTER_FRAGMENT_LIMIT` | `64` | feeder poll batch size |
| `FRONTEND_ADAPTER_IDLE_SLEEP_MS` | `1` | feeder idle backoff |
| `FRONTEND_ADAPTER_RECORDING_MAX_EVENTS` | `0` | optional cap on DB or recording source |

## Endpoints

UDF datafeed (TradingView Lightweight Charts):

- `GET /datafeed/config`
- `GET /datafeed/symbols?symbol=<ticker>`
- `GET /datafeed/search?query=<q>&limit=<n>`
- `GET /datafeed/history?symbol=<ticker>&resolution=<1S|5S|30S|1|5|15|60>&from=<unix>&to=<unix>`
- `GET /datafeed/time`

`from` and `to` accept seconds or milliseconds (`> 10_000_000_000` is treated as
ms). History returns `{ s: "no_data" }` when no bars fall inside the window.

Companion REST:

- `GET /symbols` — `{ symbols: [{ symbol, latest_event_ts_ms }] }`
- `GET /quotes?symbols=<csv>` — latest `feature.bbo` per market
- `GET /health` — status + store size + feature-plant counters
- `GET /metrics` — Prometheus text

CORS: every response carries `Access-Control-Allow-Origin: *` and
`Access-Control-Allow-Methods: GET, OPTIONS`. `OPTIONS` preflights return 204.

## Local Run

```bash
mvn -q -DskipTests package

FRONTEND_ADAPTER_PORT=8090 \
FRONTEND_ADAPTER_DB_URL=jdbc:postgresql://127.0.0.1:5432/kalshi \
FRONTEND_ADAPTER_STREAMS=canonical.trade,canonical.ticker,derived.top_of_book \
FRONTEND_ADAPTER_MODULES=bbo,ticker_snapshot,trade_tape \
java -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.frontend.FrontendAdapterMain
```

For an explicit legacy/demo recording run, set `FRONTEND_ADAPTER_SOURCE=recording`
and `FRONTEND_ADAPTER_RECORDING_ROOT=recordings`.

## Docker Compose

A `frontend-adapter` service is declared in the `frontend-integration` profile:

```bash
FRONTEND_ADAPTER_DB_URL=jdbc:postgresql://db:5432/kalshi \
docker compose --profile frontend-integration up frontend-adapter
```

It defaults to canonical DB rows and falls back from `FRONTEND_ADAPTER_DB_*` to
`DB_WRITER_DATABASE_*`. It still mounts `./recordings` read-only so explicit
`FRONTEND_ADAPTER_SOURCE=recording` demo/debug runs can read local fixtures.
Use `FRONTEND_ADAPTER_SOURCE=aeron` only when you want to drive it from a
running tickerplant channel.

## Curl Smoke Tests

```bash
curl -s http://127.0.0.1:8090/health | jq
curl -s http://127.0.0.1:8090/metrics | head
curl -s 'http://127.0.0.1:8090/datafeed/config' | jq
curl -s 'http://127.0.0.1:8090/datafeed/search?query=KX&limit=5' | jq
curl -s 'http://127.0.0.1:8090/datafeed/symbols?symbol=KXHIGHCHI-26MAY12-T70' | jq
curl -s "http://127.0.0.1:8090/datafeed/history?symbol=KXHIGHCHI-26MAY12-T70&resolution=1S&from=$(($(date -u +%s)-3600))&to=$(date -u +%s)" | jq
curl -s http://127.0.0.1:8090/datafeed/time
curl -s 'http://127.0.0.1:8090/symbols' | jq
curl -s 'http://127.0.0.1:8090/quotes?symbols=KXHIGHCHI-26MAY12-T70,KXHIGHCHI-26MAY12-T75' | jq
```

## Limits and Non-Goals

- No WebSocket streaming. The UDF protocol is HTTP polling; that's sufficient
  for the demo chart.
- No replay session management. DB source excludes replay rows by default; set
  `FRONTEND_ADAPTER_DB_INCLUDE_REPLAY=true` or a
  `FRONTEND_ADAPTER_DB_REPLAY_ID` for replay-specific reads. There is no
  pause/seek API.
- No authorization. Bind to `127.0.0.1` unless you've fronted the service.
- No PTP-aware code paths. `eventTsMs` from `FeatureOutput` is authoritative.

## Related Docs

- `docs/featureplant_runbook.md` — upstream feature module catalog
- `docs/backend_stream_contracts.md` — canonical stream identities
- `docs/stream_recorder_runbook.md` — how `recordings/canonical` is produced
