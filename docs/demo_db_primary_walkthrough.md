# DB-Primary Demo Walkthrough

This is the video/demo path for showing the landed DB-primary stack without
depending on live websocket ingestion. Keep credentials in environment variables
or mounted secrets; do not print private keys, DB passwords, or `.env` contents.

## Preconditions

- Postgres/Timescale is reachable and migrations have run.
- Kalshi REST credentials are available only through env/secrets if the
  historical backfill needs authenticated endpoints.
- The demo market universe is bounded with explicit tickers, a series ticker, or
  a small `HISTORICAL_BACKFILL_MAX_TICKERS`.
- Local examples use the `local-db` profile. EC2 examples assume the deployed
  directory already has `.env` and secrets configured.

## 1. Backfill DB Rows

Local DB setup:

```bash
docker compose --profile local-db up -d timescaledb
docker compose --profile local-db run --rm db-migrate
```

Run a bounded historical REST backfill into DB targets:

```bash
DB_URL='jdbc:postgresql://timescaledb:5432/kalshi_test'

docker compose --profile historical-backfill run --rm \
  -e HISTORICAL_BACKFILL_DB_URL="$DB_URL" \
  -e HISTORICAL_BACKFILL_CANONICAL_TARGET=db \
  -e HISTORICAL_BACKFILL_RAW_REST_TARGET=db \
  -e HISTORICAL_BACKFILL_MAX_TICKERS=25 \
  -e HISTORICAL_BACKFILL_MAX_PAGES=1 \
  -e HISTORICAL_BACKFILL_LIMIT=1000 \
  -e HISTORICAL_BACKFILL_INCLUDE_MARKETS=true \
  -e HISTORICAL_BACKFILL_INCLUDE_TRADES=true \
  historical-backfill
```

Use `HISTORICAL_BACKFILL_TICKERS` or `HISTORICAL_BACKFILL_SERIES_TICKER` for a
repeatable market set. Keep `HISTORICAL_BACKFILL_DRY_RUN=false` for the actual
demo seed.

EC2 shape:

```bash
ssh "$EC2_USER@$EC2_HOST" '
  cd "$DEPLOY_PATH" &&
  sudo docker compose --env-file .env --profile historical-backfill run --rm \
    -e HISTORICAL_BACKFILL_MAX_TICKERS=25 \
    -e HISTORICAL_BACKFILL_MAX_PAGES=1 \
    historical-backfill
'
```

## 2. Persist Feature Outputs

Run FeaturePlant from canonical DB rows and write feature rows to
`feature_outputs`:

```bash
docker compose --profile featureplant run --rm \
  -e FEATUREPLANT_DB_URL="$DB_URL" \
  -e FEATUREPLANT_SOURCE=db \
  -e FEATUREPLANT_OUTPUT=db \
  -e FEATUREPLANT_MODULES=bbo,ticker_snapshot,trade_tape \
  -e FEATUREPLANT_MAX_EVENTS=10000 \
  -e FEATUREPLANT_RUN_ONCE=true \
  featureplant
```

`FEATUREPLANT_OUTPUT=stdout` remains the default. Use `db` only for this
persisted-feature demo path.

EC2 shape:

```bash
ssh "$EC2_USER@$EC2_HOST" '
  cd "$DEPLOY_PATH" &&
  sudo docker compose --env-file .env --profile featureplant run --rm \
    -e FEATUREPLANT_OUTPUT=db \
    -e FEATUREPLANT_MAX_EVENTS=10000 \
    featureplant
'
```

## 3. Serve Persisted Features

Start the frontend adapter in persisted feature-output mode. This loads a
bounded startup snapshot from `feature_outputs`; it does not refresh until the
service restarts.

```bash
docker compose --profile frontend-integration up -d --build frontend-adapter
```

For local one-off runs, override the mode explicitly:

```bash
FRONTEND_ADAPTER_DB_URL="$DB_URL" \
FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs \
FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS=10000 \
docker compose --profile frontend-integration up -d --build frontend-adapter
```

EC2 shape:

```bash
ssh "$EC2_USER@$EC2_HOST" '
  cd "$DEPLOY_PATH" &&
  sudo env FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs \
    docker compose --env-file .env --profile frontend-integration up -d --build frontend-adapter
'
```

## 4. Smoke The Demo

Run the smoke script from the machine that can reach the frontend adapter:

```bash
FRONTEND_BASE_URL=http://127.0.0.1:8090 \
DEMO_FEATURE=feature.bbo \
DEMO_LIMIT=5 \
scripts/db-primary-demo-smoke.sh
```

Expected output shape:

```text
PASS health service=frontend-adapter feature_source=feature_outputs
PASS symbols selected=<ticker>
PASS features symbol=<ticker> feature=feature.bbo count=<n>
PASS quotes symbol=<ticker>
PASS datafeed_config
PASS db_primary_demo_smoke base_url=http://127.0.0.1:8090 symbol=<ticker> feature=feature.bbo count=<n>
```

Set `DEMO_SYMBOL=<ticker>` to pin the video to a known market. The script uses
`curl` and Python stdlib JSON parsing; it does not require `jq`.

## Curl Checks

```bash
curl -fsS http://127.0.0.1:8090/health
curl -fsS http://127.0.0.1:8090/symbols
curl -fsS 'http://127.0.0.1:8090/features?symbol=<ticker>&feature=feature.bbo&limit=5'
curl -fsS 'http://127.0.0.1:8090/quotes?symbols=<ticker>'
curl -fsS http://127.0.0.1:8090/datafeed/config
```

## Non-Goals

- No live websocket ingestion.
- No frontend refresh loop for `feature_outputs`; restart to load newer rows.
- No S3/NDJSON fallback.
- No DB schema changes.
