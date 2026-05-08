# Frontend Integration Runbook

## Selected Tools

- Charting: TradingView Lightweight Charts, served from `frontend/tradingview-lightweight`.
- Operational dashboarding: Grafana with Prometheus.
- Replay viewing: adapter-managed replay sessions over the same `/stream` WebSocket envelope used by live events.

The integration service is `edu.illinois.group8.integration.IntegrationGatewayServer`. Despite the legacy class name, it is deployed as the `frontend-adapter` module. It is not an additional gateway in the tickerplant hot path. It is a pluggable consumer module that consumes the recorder-backed normalized stream files and exposes frontend-compatible interfaces.

Historical frontend queries are backed by `edu.illinois.group8.recorder.TickerplantStreamRecorder`, deployed as `stream-recorder`. The recorder is itself a tickerplant stream consumer and writes normalized stream events under `/app/recordings/canonical/...`. The frontend adapter seeds its in-memory projection from those recordings, then tails appended recorder files for updates. It does not subscribe to Aeron unless `FRONTEND_ADAPTER_ENABLE_STREAM=true` is explicitly set for diagnostics. Neither component reads raw Kalshi JSON or bypasses canonical stream contracts.

## Local Demo

```bash
cp .env.example .env
docker compose --profile frontend-integration up --build
```

Open:

- Chart demo: http://127.0.0.1:8091
- Same-origin public proxy: http://127.0.0.1:8093
- Frontend adapter health: http://127.0.0.1:8090/health
- Grafana: http://127.0.0.1:3000
- Prometheus: http://127.0.0.1:9090

The chart demo loads symbols and bars accumulated by the frontend adapter from recorder output. If the adapter has just started and no recorder files exist yet, historical endpoints remain empty until `stream-recorder` persists normalized events.

Production-like local flow:

```text
tickerplant normalized streams
  -> stream-recorder
      -> /app/recordings/canonical
          -> frontend-adapter
      -> chart/datafeed APIs and replay controls
```

## Adapter API

```text
GET /symbols
GET /symbols/{ticker}
GET /history?symbol=...&resolution=1&from=...&to=...
GET /quotes?symbols=...
GET /depth?symbol=...
GET /trades?symbol=...&from=...&to=...
GET /open-interest?symbol=...&from=...&to=...
GET /schema
GET /metrics
GET /stream
GET /replay/sessions
POST /replay/sessions
POST /replay/sessions/{id}/pause
POST /replay/sessions/{id}/resume
POST /replay/sessions/{id}/seek
POST /replay/sessions/{id}/stop
```

TradingView UDF-style endpoints:

```text
GET /datafeed/config
GET /datafeed/search?query=...
GET /datafeed/symbols?symbol=...
GET /datafeed/history?symbol=...&resolution=...&from=...&to=...
GET /time
```

Time parameters may be Unix seconds or milliseconds. Prices are returned as probability dollars from `0.0` to `1.0`.

## Example Requests

```bash
curl http://127.0.0.1:8090/symbols
curl "http://127.0.0.1:8090/history?symbol=KXHIGHCHI-26MAY02-B53.5&resolution=1&from=1777680000&to=1777766400"
curl "http://127.0.0.1:8090/quotes?symbols=KXHIGHCHI-26MAY02-B53.5"
curl -X POST http://127.0.0.1:8090/replay/sessions \
  -H 'content-type: application/json' \
  -d '{"symbols":["KXHIGHCHI-26MAY02-B53.5"],"speed":10,"mode":"multiplier"}'
```

## Stream-Consumer Contract

The recorder and adapter are pluggable modules:

- `stream-recorder` subscribes to `STREAM_RECORDER_STREAMS`.
- `stream-recorder` persists normalized events to `STREAM_RECORDER_OUTPUT_ROOT`.
- `frontend-adapter` consumes `FRONTEND_ADAPTER_HISTORY_ROOT`, tails new `.ndjson` lines, and builds a bounded in-memory projection for frontend-compatible APIs. Point this at `/app/recordings/producer-canonical` to use the producer-side canonical recording without adding another tickerplant consumer.
- `FRONTEND_ADAPTER_ENABLE_STREAM=false` by default; keep it false for the frontend path so the adapter does not subscribe to Aeron directly.
- Both modules can be disabled or replaced without changing tickerplant publication.
- Neither module should read raw Kalshi storage directly in production.

## Timestamp Source And EC2 PTP

The current EC2 deployment is a `c7i.2xlarge` in the New York City Local Zone. After enabling ENA PHC and rebooting, the host exposes `/dev/ptp_ena -> /dev/ptp0`, and chrony can select `PHC0` as its stratum-1 source.

PTP is useful for this architecture because the recorder is the durable source of truth for historical frontend data and storage latency needs accurate timestamps. It is not required for chart rendering, and Java WebSocket/TLS application messages cannot directly consume per-packet hardware timestamps without a lower-level socket path. For that reason, timestamping remains configurable:

- `STREAM_RECORDER_TIMESTAMP_SOURCE=system_nano`: default monotonic timestamps for local storage stage durations.
- `STREAM_RECORDER_TIMESTAMP_SOURCE=ptp_system_clock`: wall-clock nanoseconds from the OS clock, useful only when chrony is disciplined by PHC/PTP.
- `FRONTEND_ADAPTER_TIMESTAMP_SOURCE=system_nano`: default monotonic timestamps for relative latency.
- `FRONTEND_ADAPTER_TIMESTAMP_SOURCE=ptp_system_clock`: wall-clock nanoseconds from the OS clock, useful only when chrony is disciplined by PHC/PTP.
- `EC2_PTP_DEVICE=/dev/ptp_ena`: PHC device checked by health and metrics.
- `ENABLE_EC2_PTP=true`: GitHub Actions runs `scripts/configure-ec2-ptp.sh` on the EC2 host during deploy.

The recorder and adapter expose `timestamp_source` in `/health`. In Docker, `ptp_device_present` is true when the device is visible inside the process or when `ENABLE_EC2_PTP=true` tells the service that the host clock is PHC-disciplined; `ptp_device_visible_to_process` reports direct device visibility separately.

## Docker Compose Profiles

- `cluster-live`: starts the existing cluster, WebSocket client, streamtap, stream recorder, and frontend adapter.
- `frontend-integration`: starts the stream recorder, frontend adapter, chart demo, streamtap, Prometheus, and Grafana.
- `replay`: runs the existing replay CLI.

For production, `FRONTEND_ADAPTER_HOST_PORT`, `CHART_DEMO_HOST_PORT`, and `FRONTEND_PUBLIC_PROXY_HOST_PORT` are bound to `127.0.0.1` on the EC2 host by default. Set `FRONTEND_ADAPTER_BIND_ADDR=0.0.0.0` and `CHART_DEMO_BIND_ADDR=0.0.0.0` only when those direct endpoints should be reachable from the open internet. The public proxy serves the chart and proxies `/api/*` to the adapter so browser tests can use one origin.

## Troubleshooting

- Empty `/symbols`: confirm `stream-recorder` has events in `/events` or files under `/app/recordings/canonical`, then confirm the adapter has `seed_journal_enabled=true` and `storage_consumer.running=true` in `/health`.
- Live chart does not update: check `GET /health` and verify `storage_consumer.events_read` advances after recorder files append; then check recorder `GET /health`.
- Replay emits nothing: confirm the requested symbol and time range exist in the adapter's bounded in-memory event window.
- Grafana panels are empty: check Prometheus targets at http://127.0.0.1:9090/targets.
- PTP metric is `0`: verify `/dev/ptp_ena` exists on the host and that `ENABLE_EC2_PTP=true` has run followed by a reboot if ENA was already loaded.
