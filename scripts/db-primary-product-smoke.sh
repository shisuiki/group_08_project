#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

LOCAL_DB_NAME="${LOCAL_DB_NAME:-kalshi_test}"
LOCAL_DB_USER="${LOCAL_DB_USER:-kalshi}"
LOCAL_DB_PASSWORD="${LOCAL_DB_PASSWORD:-kalshi}"
FEATUREPLANT_DB_URL="${FEATUREPLANT_DB_URL:-jdbc:postgresql://timescaledb:5432/${LOCAL_DB_NAME}}"
FEATUREPLANT_DB_USER="${FEATUREPLANT_DB_USER:-$LOCAL_DB_USER}"
FEATUREPLANT_DB_PASSWORD="${FEATUREPLANT_DB_PASSWORD:-$LOCAL_DB_PASSWORD}"
FEATUREPLANT_DB_CURSOR_NAME="${FEATUREPLANT_DB_CURSOR_NAME:-db-primary-product-smoke}"
FEATUREPLANT_STREAMS="${FEATUREPLANT_STREAMS:-canonical.trade,canonical.ticker,derived.top_of_book}"
FEATUREPLANT_MODULES="${FEATUREPLANT_MODULES:-bbo,ticker_snapshot,trade_tape}"
FEATUREPLANT_BATCH_SIZE="${FEATUREPLANT_BATCH_SIZE:-100}"
FEATUREPLANT_IDLE_SLEEP_MS="${FEATUREPLANT_IDLE_SLEEP_MS:-1}"
FRONTEND_ADAPTER_DB_URL="${FRONTEND_ADAPTER_DB_URL:-$FEATUREPLANT_DB_URL}"
FRONTEND_ADAPTER_DB_USER="${FRONTEND_ADAPTER_DB_USER:-$FEATUREPLANT_DB_USER}"
FRONTEND_ADAPTER_DB_PASSWORD="${FRONTEND_ADAPTER_DB_PASSWORD:-$FEATUREPLANT_DB_PASSWORD}"
FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS="${FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS:-500}"
DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT="${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT:-${FRONTEND_ADAPTER_HOST_PORT:-8090}}"
FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT}}"
FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
DEMO_SYMBOL="${DEMO_SYMBOL:-DEMO-DBPRIMARY-26MAY19-T50}"
SMOKE_HTTP_ATTEMPTS="${SMOKE_HTTP_ATTEMPTS:-30}"
SMOKE_HTTP_RETRY_SLEEP_SECONDS="${SMOKE_HTTP_RETRY_SLEEP_SECONDS:-1}"
SMOKE_DB_ATTEMPTS="${SMOKE_DB_ATTEMPTS:-60}"
SMOKE_DB_RETRY_SLEEP_SECONDS="${SMOKE_DB_RETRY_SLEEP_SECONDS:-1}"
EXPECTED_FEATURE_OUTPUTS_MIN="${EXPECTED_FEATURE_OUTPUTS_MIN:-1}"

export LOCAL_DB_NAME LOCAL_DB_USER LOCAL_DB_PASSWORD
export FEATUREPLANT_DB_URL FEATUREPLANT_DB_USER FEATUREPLANT_DB_PASSWORD
export FEATUREPLANT_DB_CURSOR_NAME FEATUREPLANT_STREAMS FEATUREPLANT_MODULES
export FEATUREPLANT_BATCH_SIZE FEATUREPLANT_IDLE_SLEEP_MS
export FRONTEND_ADAPTER_DB_URL FRONTEND_ADAPTER_DB_USER FRONTEND_ADAPTER_DB_PASSWORD
export FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS
export DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT

cd "$REPO_ROOT"

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/db-primary-product-smoke.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT INT HUP TERM

print_product_diagnostics() {
    printf '\nDocker Compose status:\n' >&2
    docker compose --profile db-primary-product ps >&2 || true
    printf '\nRecent frontend/product FeaturePlant logs:\n' >&2
    docker compose --profile db-primary-product logs --tail=120 \
        frontend-adapter-db-primary featureplant-db-follower >&2 || true
}

sql_literal() {
    printf '%s' "$1" | sed "s/'/''/g"
}

psql_scalar() {
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 -c "$1" \
        | tr -d '\r'
}

demo_feature_outputs() {
    psql_scalar "select count(*) from feature_outputs where source_event_id like 'demo-db-primary-canonical-%'"
}

cursor_commit_seq() {
    cursor_name="$(sql_literal "$FEATUREPLANT_DB_CURSOR_NAME")"
    psql_scalar "select coalesce((select last_commit_seq from featureplant_cursors where cursor_name = '${cursor_name}'), 0)"
}

wait_frontend_started() {
    health_file="$tmpdir/product-health.json"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FRONTEND_BASE_URL}/health" -o "$health_file" \
            && python3 - "$health_file" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
if body.get("service") != "frontend-adapter":
    raise SystemExit("service mismatch")
if body.get("feature_source") != "feature_outputs":
    raise SystemExit("feature source mismatch")
refresh = body.get("feature_output_refresh")
if not isinstance(refresh, dict):
    raise SystemExit("feature output refresh is missing")
if refresh.get("enabled") is not True or refresh.get("running") is not True:
    raise SystemExit("feature output refresh is not running")
raw_total_loaded = refresh.get("total_loaded")
if isinstance(raw_total_loaded, bool) or not isinstance(raw_total_loaded, int):
    raise SystemExit("feature output refresh total_loaded is not an integer")
PY
        then
            python3 - "$health_file" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
refresh = body.get("feature_output_refresh", {})
print(body.get("started_at", ""))
print(refresh.get("total_loaded", 0))
PY
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'frontend adapter did not become healthy at %s\n' "$FRONTEND_BASE_URL" >&2
            print_product_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

wait_featureplant_followed_seed() {
    expected_commit_seq="$1"
    attempt=1
    while :; do
        output_count="$(demo_feature_outputs)"
        current_cursor_seq="$(cursor_commit_seq)"
        if [ "$output_count" -ge "$EXPECTED_FEATURE_OUTPUTS_MIN" ] \
            && [ "$current_cursor_seq" -ge "$expected_commit_seq" ]; then
            printf '%s\n%s\n' "$output_count" "$current_cursor_seq"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_DB_ATTEMPTS" ]; then
            printf 'FeaturePlant follower did not consume demo seed: feature_outputs=%s expected_at_least=%s cursor=%s expected_cursor_at_least=%s\n' \
                "$output_count" "$EXPECTED_FEATURE_OUTPUTS_MIN" "$current_cursor_seq" "$expected_commit_seq" >&2
            print_product_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_DB_RETRY_SLEEP_SECONDS"
    done
}

docker compose --profile db-primary-product stop featureplant-db-follower >/dev/null 2>&1 || true

"$SCRIPT_DIR/db-primary-demo-seed.sh"

expected_commit_seq="$(
    psql_scalar "select coalesce(max(canonical_commit_seq), 0) from canonical_events where event_id like 'demo-db-primary-canonical-%'"
)"
if [ "$expected_commit_seq" -le 0 ]; then
    printf 'demo seed did not create canonical commit sequence rows\n' >&2
    exit 1
fi

FEATUREPLANT_RUN_ONCE=false \
FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs \
FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_ENABLED=true \
FRONTEND_ADAPTER_METADATA_SOURCE=auto \
docker compose --profile db-primary-product up -d --build --force-recreate frontend-adapter-db-primary

frontend_health="$(wait_frontend_started)"
frontend_started_at="$(printf '%s\n' "$frontend_health" | sed -n '1p')"
frontend_initial_loaded="$(printf '%s\n' "$frontend_health" | sed -n '2p')"

FEATUREPLANT_SOURCE=db \
FEATUREPLANT_OUTPUT=db \
FEATUREPLANT_RUN_ONCE=false \
FEATUREPLANT_DB_INCLUDE_REPLAY=false \
docker compose --profile db-primary-product up -d --build --force-recreate featureplant-db-follower

follow_result="$(wait_featureplant_followed_seed "$expected_commit_seq")"
feature_outputs_after="$(printf '%s\n' "$follow_result" | sed -n '1p')"
cursor_after="$(printf '%s\n' "$follow_result" | sed -n '2p')"

FRONTEND_BASE_URL="$FRONTEND_BASE_URL" \
EXPECTED_FRONTEND_STARTED_AT="$frontend_started_at" \
EXPECTED_REFRESH_TOTAL_LOADED_MIN=1 \
EXPECTED_FEATURE_SOURCE=feature_outputs \
DEMO_SYMBOL="$DEMO_SYMBOL" \
SMOKE_HTTP_ATTEMPTS="$SMOKE_HTTP_ATTEMPTS" \
SMOKE_HTTP_RETRY_SLEEP_SECONDS="$SMOKE_HTTP_RETRY_SLEEP_SECONDS" \
"$SCRIPT_DIR/db-primary-demo-smoke.sh"

printf 'PASS db_primary_product_smoke symbol=%s frontend_started_at=%s initial_loaded=%s feature_outputs=%s cursor=%s expected_cursor_at_least=%s\n' \
    "$DEMO_SYMBOL" "$frontend_started_at" "$frontend_initial_loaded" \
    "$feature_outputs_after" "$cursor_after" "$expected_commit_seq"
