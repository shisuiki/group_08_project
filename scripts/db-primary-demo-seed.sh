#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"
SEED_SQL="${DEMO_SEED_SQL:-$SCRIPT_DIR/db-primary-demo-seed.sql}"

LOCAL_DB_NAME="${LOCAL_DB_NAME:-kalshi_test}"
LOCAL_DB_USER="${LOCAL_DB_USER:-kalshi}"
LOCAL_DB_PASSWORD="${LOCAL_DB_PASSWORD:-kalshi}"
POSTGRES_HOST_PORT="${POSTGRES_HOST_PORT:-5432}"

cd "$REPO_ROOT"

if [ ! -f "$SEED_SQL" ]; then
    printf 'seed SQL not found: %s\n' "$SEED_SQL" >&2
    exit 1
fi

printf 'Starting local Timescale/Postgres...\n'
docker compose --profile local-db up -d timescaledb
printf 'PASS local_db service=timescaledb\n'

printf 'Running DB migrations...\n'
docker compose --profile local-db run --rm db-migrate
printf 'PASS db_migrate\n'

printf 'Applying DB-primary demo seed...\n'
docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
    psql -q -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 < "$SEED_SQL"

feature_count="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(*) from feature_outputs where feature_event_id like 'demo-db-primary-%'"
)"

market_count="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(*) from market_metadata where market_ticker like 'DEMO-DBPRIMARY-%'"
)"

symbols="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select string_agg(market_ticker, ', ' order by market_ticker) from (select distinct market_ticker from feature_outputs where feature_event_id like 'demo-db-primary-%') seeded"
)"

printf 'PASS demo_seed feature_outputs=%s market_metadata=%s symbols=%s\n' \
    "$feature_count" "$market_count" "$symbols"

cat <<EOF

Next local demo commands:
  FRONTEND_ADAPTER_DB_URL=jdbc:postgresql://timescaledb:5432/${LOCAL_DB_NAME} \\
  FRONTEND_ADAPTER_DB_USER=${LOCAL_DB_USER} \\
  FRONTEND_ADAPTER_DB_PASSWORD=<local-db-password> \\
  FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs \\
  docker compose --profile frontend-integration up -d --build frontend-adapter

  DEMO_SYMBOL=DEMO-DBPRIMARY-26MAY19-T50 scripts/db-primary-demo-smoke.sh

Host psql tools can use 127.0.0.1:${POSTGRES_HOST_PORT}; containers use timescaledb:5432.
EOF
