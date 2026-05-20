#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"
PRODUCT_DEMO_SCENARIO="${PRODUCT_DEMO_SCENARIO:-baseline}"
case "$PRODUCT_DEMO_SCENARIO" in
    baseline|"")
        default_seed_sql="$SCRIPT_DIR/db-primary-demo-seed.sql"
        ;;
    long-replay)
        default_seed_sql="$SCRIPT_DIR/db-primary-demo-long-replay-seed.sql"
        ;;
    *)
        printf 'unsupported PRODUCT_DEMO_SCENARIO: %s\n' "$PRODUCT_DEMO_SCENARIO" >&2
        exit 2
        ;;
esac
SEED_SQL="${DEMO_SEED_SQL:-$default_seed_sql}"

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

canonical_count="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(*) from canonical_events where event_id like 'demo-db-primary-canonical-%'"
)"

feature_count="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(*) from feature_outputs where source_event_id like 'demo-db-primary-canonical-%'"
)"

latest_state_count="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(*) from latest_market_state where market_ticker like 'DEMO-DBPRIMARY-%'"
)"

market_count="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(*) from market_metadata where market_ticker like 'DEMO-DBPRIMARY-%'"
)"

symbols="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select string_agg(market_ticker, ', ' order by market_ticker) from (select distinct market_ticker from canonical_events where event_id like 'demo-db-primary-canonical-%') seeded"
)"

if [ "$feature_count" != "0" ]; then
    printf 'demo seed expected feature_outputs=0 before FeaturePlant, got %s\n' "$feature_count" >&2
    exit 1
fi

if [ "$latest_state_count" != "0" ]; then
    printf 'demo seed expected latest_market_state=0 before FeaturePlant, got %s\n' "$latest_state_count" >&2
    exit 1
fi

printf 'PASS demo_seed scenario=%s canonical_events=%s feature_outputs=%s latest_market_state=%s market_metadata=%s symbols=%s\n' \
    "$PRODUCT_DEMO_SCENARIO" "$canonical_count" "$feature_count" "$latest_state_count" "$market_count" "$symbols"

cat <<EOF

Next local demo commands:
  FRONTEND_ADAPTER_DB_URL=jdbc:postgresql://timescaledb:5432/${LOCAL_DB_NAME} \\
  FRONTEND_ADAPTER_DB_USER=${LOCAL_DB_USER} \\
  FRONTEND_ADAPTER_DB_PASSWORD=<local-db-password> \\
  FRONTEND_ADAPTER_FEATURE_SOURCE=latest_market_state \\
  docker compose --profile frontend-integration up -d --build frontend-adapter

  scripts/db-primary-demo-run-featureplant.sh

  DEMO_SYMBOL=DEMO-DBPRIMARY-26MAY19-T50 scripts/db-primary-demo-smoke.sh

Or run the full local no-credential pipeline:
  scripts/db-primary-demo-pipeline.sh

Host psql tools can use 127.0.0.1:${POSTGRES_HOST_PORT}; containers use timescaledb:5432.
EOF
