# DB-Primary Demo Walkthrough

This is the local video/demo path for showing the landed DB-seeded
persisted-feature frontend demo without Kalshi live/backfill credentials,
NDJSON, S3, or external network dependencies. Use
`docs/video_demo_checklist.md` as the presentation guardrail. Keep credentials
in environment variables or mounted secrets; do not print private keys, DB
passwords, or `.env` contents.

## Preconditions

- Docker Compose can start the local `timescaledb` service.
- Migrations have run before the frontend adapter starts.
- The no-credential local rehearsal uses `scripts/db-primary-demo-seed.sh`,
  which writes deterministic demo rows directly to `feature_outputs` and
  `market_metadata`.
- Containers use `jdbc:postgresql://timescaledb:5432/kalshi_test`. Host tools
  such as local `psql` use `127.0.0.1:${POSTGRES_HOST_PORT:-5432}`.

## 1. Seed Local DB Rows

The seed script starts local Timescale/Postgres, runs Flyway migrations, and
applies an idempotent demo seed. It deletes only rows with the demo prefix or
demo tickers before inserting fresh rows; it does not truncate shared tables.

```bash
scripts/db-primary-demo-seed.sh
```

Internally, the script runs:

```bash
docker compose --profile local-db up -d timescaledb
docker compose --profile local-db run --rm db-migrate
```

Expected seed output includes:

```text
PASS local_db service=timescaledb
PASS db_migrate
PASS demo_seed feature_outputs=24 market_metadata=2 symbols=DEMO-DBPRIMARY-26MAY19-T50, DEMO-DBPRIMARY-26MAY19-T60
```

The seed anchors event timestamps inside the most recent 15 minutes at seed
time, so `frontend/tradingview-lightweight/index.html` has bars in its current
15m, 1h, 6h, and 24h windows after the frontend adapter restarts. Rerun the
seed before recording if the demo has been idle long enough for the 15m window
to age out.

## 2. Serve Persisted Features

Start the frontend adapter in persisted feature-output mode. This loads a
bounded startup snapshot from `feature_outputs`; it does not refresh until the
service restarts.

```bash
FRONTEND_ADAPTER_DB_URL=jdbc:postgresql://timescaledb:5432/kalshi_test \
FRONTEND_ADAPTER_DB_USER="${LOCAL_DB_USER:-kalshi}" \
FRONTEND_ADAPTER_DB_PASSWORD="${LOCAL_DB_PASSWORD:-kalshi}" \
FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs \
FRONTEND_ADAPTER_FEATURE_OUTPUT_MAX_ROWS=10000 \
docker compose --profile frontend-integration up -d --build frontend-adapter
```

EC2 shape, assuming `.env` already has the deployed DB URL and credentials:

```bash
ssh "$EC2_USER@$EC2_HOST" '
  cd "$DEPLOY_PATH" &&
  sudo env FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs \
    docker compose --env-file .env --profile frontend-integration up -d --build frontend-adapter
'
```

## 3. Smoke The Demo

Run the smoke script from the machine that can reach the frontend adapter:

```bash
FRONTEND_BASE_URL=http://127.0.0.1:8090 \
DEMO_SYMBOL=DEMO-DBPRIMARY-26MAY19-T50 \
DEMO_FEATURE=feature.bbo \
DEMO_LIMIT=5 \
scripts/db-primary-demo-smoke.sh
```

Expected output shape:

```text
PASS health service=frontend-adapter feature_source=feature_outputs expected_feature_source=feature_outputs
PASS symbols selected=DEMO-DBPRIMARY-26MAY19-T50
PASS features symbol=DEMO-DBPRIMARY-26MAY19-T50 feature=feature.bbo count=<n>
PASS quotes symbol=DEMO-DBPRIMARY-26MAY19-T50
PASS datafeed_history symbol=DEMO-DBPRIMARY-26MAY19-T50 resolution=1 bars=<n>
PASS datafeed_config
PASS db_primary_demo_smoke base_url=http://127.0.0.1:8090 symbol=DEMO-DBPRIMARY-26MAY19-T50 feature=feature.bbo count=<n> history_bars=<n>
```

Set `DEMO_SYMBOL` only when pinning the video to a known market. Without it,
the script selects the first symbol from `/symbols`. The smoke script uses
`curl` and Python stdlib JSON parsing; it does not require `jq`. By default it
requires `/health` to report `feature_source=feature_outputs`, so it will fail
instead of passing against the frontend adapter's default module-driven mode. It
retries HTTP fetches briefly so it can be run immediately after Compose starts
the adapter.

## Curl Checks

```bash
curl -fsS http://127.0.0.1:8090/health
curl -fsS http://127.0.0.1:8090/symbols
curl -fsS 'http://127.0.0.1:8090/features?symbol=DEMO-DBPRIMARY-26MAY19-T50&feature=feature.bbo&limit=5'
curl -fsS 'http://127.0.0.1:8090/quotes?symbols=DEMO-DBPRIMARY-26MAY19-T50'
curl -fsS "http://127.0.0.1:8090/datafeed/history?symbol=DEMO-DBPRIMARY-26MAY19-T50&resolution=1&from=$(($(date -u +%s)-7200))&to=$(date -u +%s)"
curl -fsS http://127.0.0.1:8090/datafeed/config
```

## Optional Credentialed Pipeline

For real market rows, the DB-primary path remains:

```bash
DB_URL='jdbc:postgresql://timescaledb:5432/kalshi_test'

docker compose --profile historical-backfill run --rm \
  -e HISTORICAL_BACKFILL_DB_URL="$DB_URL" \
  -e HISTORICAL_BACKFILL_CANONICAL_TARGET=db \
  -e HISTORICAL_BACKFILL_RAW_REST_TARGET=db \
  -e HISTORICAL_BACKFILL_MAX_TICKERS=25 \
  -e HISTORICAL_BACKFILL_MAX_PAGES=1 \
  historical-backfill

docker compose --profile featureplant run --rm \
  -e FEATUREPLANT_DB_URL="$DB_URL" \
  -e FEATUREPLANT_SOURCE=db \
  -e FEATUREPLANT_OUTPUT=db \
  -e FEATUREPLANT_MAX_EVENTS=10000 \
  -e FEATUREPLANT_RUN_ONCE=true \
  featureplant
```

Use this only when credentials and network access are intentionally part of the
demo rehearsal. The stable local seed above is the default no-credential path.

## Non-Goals

- No live websocket ingestion.
- No frontend refresh loop for `feature_outputs`; restart to load newer rows.
- No S3/NDJSON fallback.
- No DB schema changes.
