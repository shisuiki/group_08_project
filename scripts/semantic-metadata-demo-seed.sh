#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"
SEED_SQL="${SEMANTIC_METADATA_DEMO_SEED_SQL:-$SCRIPT_DIR/semantic-metadata-demo-seed.sql}"
PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS="${PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS:-120}"
SEMANTIC_DEMO_RUN_MIGRATIONS="${SEMANTIC_DEMO_RUN_MIGRATIONS:-true}"
LOCAL_DB_NAME="${LOCAL_DB_NAME:-kalshi_test}"
LOCAL_DB_USER="${LOCAL_DB_USER:-kalshi}"
LOCAL_DB_PASSWORD="${LOCAL_DB_PASSWORD:-kalshi}"

case "$PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS" in
    ''|*[!0-9]*)
        printf 'PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS must be a positive integer, got %s\n' \
            "$PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS" >&2
        exit 2
        ;;
esac
if [ "$PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS" -lt 1 ]; then
    printf 'PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS must be at least 1, got %s\n' \
        "$PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS" >&2
    exit 2
fi

cd "$REPO_ROOT"

if [ ! -f "$SEED_SQL" ]; then
    printf 'semantic metadata seed SQL not found: %s\n' "$SEED_SQL" >&2
    exit 1
fi

printf 'Starting local Timescale/Postgres...\n'
docker compose --profile local-db up -d timescaledb
printf 'PASS local_db service=timescaledb\n'

case "$SEMANTIC_DEMO_RUN_MIGRATIONS" in
    1|true|TRUE|yes|YES|on|ON)
        printf 'Running DB migrations...\n'
        docker compose --profile local-db run --rm db-migrate
        printf 'PASS db_migrate\n'
        ;;
    *)
        ;;
esac

printf 'Applying semantic metadata demo fixture rows=%s...\n' "$PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS"
docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
    psql -q -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -v fixture_rows="$PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS" < "$SEED_SQL"

semantic_count="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(*) from market_semantic_metadata where market_ticker like 'DEMO-SEMANTIC-%' and taxonomy_version = 'v1' and model = 'fixture/semantic-demo-v1'" \
        | tr -d '\r'
)"

market_count="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(*) from market_metadata where market_ticker like 'DEMO-SEMANTIC-%'" \
        | tr -d '\r'
)"

latest_state_count="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(*) from latest_market_state where market_ticker like 'DEMO-SEMANTIC-%'" \
        | tr -d '\r'
)"

group_count="$(
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(distinct sector) from market_semantic_metadata where market_ticker like 'DEMO-SEMANTIC-%' and taxonomy_version = 'v1'" \
        | tr -d '\r'
)"

if [ "$semantic_count" -lt "$PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS" ]; then
    printf 'semantic metadata fixture wrote too few rows: semantic_rows=%s expected_at_least=%s\n' \
        "$semantic_count" "$PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS" >&2
    exit 1
fi

printf 'PASS semantic_metadata_demo_seed semantic_rows=%s market_metadata=%s latest_market_state=%s sectors=%s prefix=DEMO-SEMANTIC rows_requested=%s\n' \
    "$semantic_count" "$market_count" "$latest_state_count" "$group_count" "$PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS"
