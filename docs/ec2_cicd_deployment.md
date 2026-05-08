# EC2 CI/CD Deployment

This repository deploys to EC2 through `.github/workflows/deploy-ec2.yml`.

## Trigger

- Every push to `main`
- Manual `workflow_dispatch`

## GitHub Secrets

Required repository secrets:

- `EC2_HOST`: EC2 public DNS name.
- `EC2_USER`: SSH user, currently `ec2-user`.
- `EC2_SSH_PRIVATE_KEY`: private SSH key used to access the instance.
- `KALSHI_KEY_ID`: Kalshi API key ID.
- `KALSHI_PRIVATE_KEY`: Kalshi RSA private key content.

## GitHub Variables

Recommended repository variables:

- `DEPLOY_PATH`: remote app path, default `/opt/group_08_project`.
- `DEPLOY_PROFILE`: Docker Compose profile, default `cluster-live`.
- `KALSHI_BASE_URL`: default `https://api.elections.kalshi.com`.
- `KALSHI_MARKET_SERIES_TICKER`: default `KXHIGHCHI`.
- `KALSHI_MARKET_SELECTION_MODE`: default `configured`. Set `open_markets` to discover every open Kalshi market at startup.
- `KALSHI_MARKET_STATUS`: default `open`.
- `KALSHI_MARKET_MVE_FILTER`: optional `only` or `exclude` filter for multivariate event/combo markets during REST discovery.
- `KALSHI_MARKET_DISCOVERY_LIMIT`: default `1000`, matching Kalshi's max page size.
- `KALSHI_MARKET_DISCOVERY_MAX_MARKETS`: default `0` for unlimited discovery. Set a positive number only for bounded soak tests.
- `KALSHI_WS_CHANNELS`: default `orderbook_delta,trade,ticker,market_lifecycle_v2`.
- `KALSHI_WS_GLOBAL_CHANNELS`: optional override for unfiltered websocket channels in open-market mode.
- `KALSHI_WS_FILTERED_CHANNELS`: optional override for market-filtered websocket channels in open-market mode.
- `KALSHI_ORDERBOOK_SUBSCRIPTION_CHUNK_SIZE`: default `100` market tickers per filtered subscription command.
- `KALSHI_ORDERBOOK_MARKETS_PER_CONNECTION`: default `10000`; opens another websocket shard after this many filtered market subscriptions.
- `KALSHI_WS_SUBSCRIPTION_DELAY_MS`: default `250` between subscription commands.
- `KALSHI_WS_ACK_TIMEOUT_MS`: default `30000`; timeout for Kalshi subscribe/update acknowledgements during open-market startup.
- `BACKEND_SOURCE_SEQUENCE_MONITOR_ENABLED`: default `false`; leave disabled for sharded open-market capture until live ingestion carries a websocket connection id.
- `BACKEND_ORDERBOOK_DERIVED_ENABLED`: default `true`; set `false` for recorder-first high-volume open-market runs.
- `AERON_EXTERNAL_CHANNEL`: default `aeron:udp?endpoint=224.0.1.1:40456`.
- `STREAM_TAP_HOST_PORT`: default `8080`, bound to `127.0.0.1` on the EC2 host.
- `STREAM_TAP_STREAMS`: comma-separated canonical stream names for the local stream tap.
- `STREAM_RECORDER_HOST_PORT`: default `8092`, bound to `127.0.0.1` on the EC2 host.
- `STREAM_RECORDER_STREAMS`: comma-separated normalized stream names persisted by the recorder.
- `STREAM_RECORDER_TIMESTAMP_SOURCE`: default `system_nano`. Use `ptp_system_clock` only when chrony is using EC2 PHC/PTP.
- `S3_RECORDING_BUCKET`: optional S3 bucket for recorder upload.
- `S3_RECORDING_PREFIX`: default `kalshi-normalized/`.
- `S3_RECORDING_SUBTREES`: default `canonical,raw-ingest,producer-canonical`.
- `S3_UPLOAD_INTERVAL_SECONDS`: default `60`.
- `S3_UPLOAD_MIN_AGE_SECONDS`: default `120`.
- `S3_DELETE_AFTER_UPLOAD`: default `false`; set `true` for whole-universe capture on the EC2 root volume.
- `FRONTEND_ADAPTER_HOST_PORT`: default `8090`.
- `FRONTEND_ADAPTER_BIND_ADDR`: default `127.0.0.1`. Set to `0.0.0.0` only when the adapter API should be public.
- `FRONTEND_ADAPTER_HISTORY_ROOT`: default `/app/recordings/producer-canonical`, the producer-side canonical source of truth. It can be pointed at `/app/recordings` to use the downstream stream recorder's `canonical` subtree.
- `FRONTEND_ADAPTER_STREAMS`: comma-separated normalized stream names available to optional adapter stream diagnostics.
- `FRONTEND_ADAPTER_ENABLE_STREAM`: default `false`. Keep false for frontend integrations so the adapter consumes recorder-backed storage instead of Aeron.
- `FRONTEND_ADAPTER_SEED_JOURNAL`: default `true`, seeds historical APIs from recorder output.
- `FRONTEND_ADAPTER_ENABLE_STORAGE_TAIL`: default `true`, keeps the adapter projection current by tailing recorder output.
- `FRONTEND_ADAPTER_STORAGE_POLL_INTERVAL_MS`: default `1000`.
- `FRONTEND_ADAPTER_TIMESTAMP_SOURCE`: default `system_nano`. Use `ptp_system_clock` only when chrony is using EC2 PHC/PTP.
- `CHART_DEMO_HOST_PORT`: default `8091`.
- `CHART_DEMO_BIND_ADDR`: default `127.0.0.1`. Set to `0.0.0.0` only when the chart demo should be public.
- `FRONTEND_PUBLIC_PROXY_HOST_PORT`: default `8093`, serves the chart and proxies `/api/*` to the adapter.
- `FRONTEND_PUBLIC_PROXY_BIND_ADDR`: default `127.0.0.1`.
- `ENABLE_EC2_PTP`: default `false`. When `true`, the workflow configures ENA PHC/PTP on the host.
- `EC2_PTP_DEVICE`: default `/dev/ptp_ena`.
- `WSCLIENT_START_DELAY_SECONDS`: default `20`, gives the Aeron cluster time to elect a leader before the live WebSocket client connects.

Optional:

- `KALSHI_MARKET_TICKERS`: comma-separated explicit market ticker list. If unset, the live client resolves open markets from `KALSHI_MARKET_SERIES_TICKER` unless `KALSHI_MARKET_SELECTION_MODE=open_markets`.

## EC2 Bootstrap

The workflow bootstraps an Amazon Linux host by installing:

- `git`
- `docker`
- Docker Compose plugin

It then clones or resets the repo at `DEPLOY_PATH`, writes runtime secrets into `DEPLOY_PATH/secrets`, writes `.env`, and starts the configured Docker Compose profile.

## Optional EC2 PTP Clock

The stream recorder and frontend adapter can report whether an EC2 PTP hardware clock is available and can use the OS wall clock as a PTP-disciplined timestamp source. PTP is most useful for the recorder because it writes durable historical data and storage latency timestamps. Enable this only on an instance family and Region/Local Zone that exposes ENA PHC.

Set `ENABLE_EC2_PTP=true` in GitHub repository variables. The deploy workflow runs `scripts/configure-ec2-ptp.sh`, which:

- writes `options ena phc_enable=1` for the ENA driver;
- creates the stable `/dev/ptp_ena` udev symlink;
- configures chrony with `refclock PHC /dev/ptp_ena poll 0 delay 0.000010 prefer` when the PHC device is present.

If `ena.phc_enable` was previously `0`, reboot the instance once so ENA reloads with PHC enabled. Verify with:

```bash
cat /sys/module/ena/parameters/phc_enable
ls -l /dev/ptp*
sudo ethtool -T "$(ls /sys/class/net | grep -Ev 'lo|docker|br-|veth' | head -1)"
chronyc sources -v
```

Expected successful state:

- `/dev/ptp_ena -> ptp0` exists.
- `ethtool -T` shows `PTP Hardware Clock: 0`.
- `chronyc sources -v` shows `#* PHC0`.

## Operational Commands

On the EC2 instance:

```bash
cd /opt/group_08_project
sudo docker compose --env-file .env --profile cluster-live ps
sudo docker compose --env-file .env --profile cluster-live logs -f --tail=200
sudo docker compose --env-file .env --profile cluster-live down
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/events
curl http://127.0.0.1:8092/health
curl http://127.0.0.1:8092/events
curl http://127.0.0.1:8090/health
curl http://127.0.0.1:8090/symbols
```

## Security Notes

The Kalshi private key and runtime `.env` are written only on the EC2 host and are not committed to the repository. The GitHub Actions workflow keeps the SSH key and Kalshi key in repository secrets.
