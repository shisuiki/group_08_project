#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

LOCAL_DB_NAME="${LOCAL_DB_NAME:-kalshi_test}"
LOCAL_DB_USER="${LOCAL_DB_USER:-kalshi}"
LOCAL_DB_PASSWORD="${LOCAL_DB_PASSWORD:-kalshi}"
FRONTEND_ADAPTER_DB_URL="${FRONTEND_ADAPTER_DB_URL:-jdbc:postgresql://timescaledb:5432/${LOCAL_DB_NAME}}"
FRONTEND_ADAPTER_DB_USER="${FRONTEND_ADAPTER_DB_USER:-$LOCAL_DB_USER}"
FRONTEND_ADAPTER_DB_PASSWORD="${FRONTEND_ADAPTER_DB_PASSWORD:-$LOCAL_DB_PASSWORD}"
FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS="${FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS:-500}"
FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://127.0.0.1:${FRONTEND_ADAPTER_HOST_PORT:-8090}}"
FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
DEMO_SYMBOL="${DEMO_SYMBOL:-DEMO-DBPRIMARY-26MAY19-T50}"
SMOKE_HTTP_ATTEMPTS="${SMOKE_HTTP_ATTEMPTS:-30}"
SMOKE_HTTP_RETRY_SLEEP_SECONDS="${SMOKE_HTTP_RETRY_SLEEP_SECONDS:-1}"

cd "$REPO_ROOT"

wait_frontend_started() {
    tmpfile="$(mktemp "${TMPDIR:-/tmp}/db-primary-demo-health.XXXXXX")"
    trap 'rm -f "$tmpfile"' EXIT INT HUP TERM
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FRONTEND_BASE_URL}/health" -o "$tmpfile" \
            && python3 - "$tmpfile" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
if body.get("service") != "frontend-adapter":
    raise SystemExit("service mismatch")
if body.get("feature_source") != "feature_outputs":
    raise SystemExit("feature source mismatch")
refresh = body.get("feature_output_refresh")
if not isinstance(refresh, dict) or refresh.get("enabled") is not True or refresh.get("running") is not True:
    raise SystemExit("feature output refresh is not running")
raw_total_loaded = refresh.get("total_loaded")
if isinstance(raw_total_loaded, bool) or not isinstance(raw_total_loaded, int):
    raise SystemExit("feature output refresh total_loaded is not an integer")
if raw_total_loaded != 0:
    raise SystemExit(f"feature output refresh loaded {raw_total_loaded} rows before FeaturePlant")
PY
        then
            python3 - "$tmpfile" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    print(json.load(handle).get("started_at", ""))
PY
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'frontend adapter did not become healthy at %s\n' "$FRONTEND_BASE_URL" >&2
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

"$SCRIPT_DIR/db-primary-demo-seed.sh"

FRONTEND_ADAPTER_DB_URL="$FRONTEND_ADAPTER_DB_URL" \
FRONTEND_ADAPTER_DB_USER="$FRONTEND_ADAPTER_DB_USER" \
FRONTEND_ADAPTER_DB_PASSWORD="$FRONTEND_ADAPTER_DB_PASSWORD" \
FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs \
FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_ENABLED=true \
FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS="$FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS" \
FRONTEND_ADAPTER_METADATA_SOURCE=auto \
docker compose --profile frontend-integration up -d --build --force-recreate frontend-adapter

frontend_started_at="$(wait_frontend_started)"

EXPECTED_FEATURE_OUTPUTS_BEFORE=0 "$SCRIPT_DIR/db-primary-demo-run-featureplant.sh"

EXPECTED_FRONTEND_STARTED_AT="$frontend_started_at" \
EXPECTED_REFRESH_TOTAL_LOADED_MIN=1 \
DEMO_SYMBOL="$DEMO_SYMBOL" \
SMOKE_HTTP_ATTEMPTS="$SMOKE_HTTP_ATTEMPTS" \
SMOKE_HTTP_RETRY_SLEEP_SECONDS="$SMOKE_HTTP_RETRY_SLEEP_SECONDS" \
"$SCRIPT_DIR/db-primary-demo-smoke.sh"

printf 'PASS db_primary_demo_pipeline symbol=%s frontend_started_at=%s\n' \
    "$DEMO_SYMBOL" "$frontend_started_at"
