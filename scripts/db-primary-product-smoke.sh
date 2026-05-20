#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

PRODUCT_DEMO_SCENARIO="${PRODUCT_DEMO_SCENARIO:-baseline}"
LOCAL_DB_NAME="${LOCAL_DB_NAME:-kalshi_test}"
LOCAL_DB_USER="${LOCAL_DB_USER:-kalshi}"
LOCAL_DB_PASSWORD="${LOCAL_DB_PASSWORD:-kalshi}"
FEATUREPLANT_DB_URL="${FEATUREPLANT_DB_URL:-jdbc:postgresql://timescaledb:5432/${LOCAL_DB_NAME}}"
FEATUREPLANT_DB_USER="${FEATUREPLANT_DB_USER:-$LOCAL_DB_USER}"
FEATUREPLANT_DB_PASSWORD="${FEATUREPLANT_DB_PASSWORD:-$LOCAL_DB_PASSWORD}"
FEATUREPLANT_DB_CURSOR_NAME="${FEATUREPLANT_DB_CURSOR_NAME:-db-primary-product-smoke}"
FEATUREPLANT_DB_INCLUDE_REPLAY="${FEATUREPLANT_DB_INCLUDE_REPLAY:-false}"
FEATUREPLANT_DB_REPLAY_ID="${FEATUREPLANT_DB_REPLAY_ID:-}"
FEATUREPLANT_STREAMS="${FEATUREPLANT_STREAMS:-canonical.trade,canonical.ticker,derived.top_of_book}"
FEATUREPLANT_MODULES="${FEATUREPLANT_MODULES:-bbo,ticker_snapshot,trade_tape}"
FEATUREPLANT_BATCH_SIZE="${FEATUREPLANT_BATCH_SIZE:-100}"
FEATUREPLANT_IDLE_SLEEP_MS="${FEATUREPLANT_IDLE_SLEEP_MS:-1}"
FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED="${FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED:-true}"
FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY="${FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY:-250000}"
FEATUREPLANT_DB_OUTPUT_BATCH_SIZE="${FEATUREPLANT_DB_OUTPUT_BATCH_SIZE:-500}"
FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS="${FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS:-5000}"
FEATUREPLANT_METRICS_HOST="${FEATUREPLANT_METRICS_HOST:-0.0.0.0}"
FEATUREPLANT_METRICS_PORT="${FEATUREPLANT_METRICS_PORT:-8094}"
FEATUREPLANT_METRICS_HOST_PORT="${FEATUREPLANT_METRICS_HOST_PORT:-8094}"
FEATUREPLANT_METRICS_BASE_URL="${FEATUREPLANT_METRICS_BASE_URL:-http://127.0.0.1:${FEATUREPLANT_METRICS_HOST_PORT}}"
FRONTEND_ADAPTER_DB_URL="${FRONTEND_ADAPTER_DB_URL:-$FEATUREPLANT_DB_URL}"
FRONTEND_ADAPTER_DB_USER="${FRONTEND_ADAPTER_DB_USER:-$FEATUREPLANT_DB_USER}"
FRONTEND_ADAPTER_DB_PASSWORD="${FRONTEND_ADAPTER_DB_PASSWORD:-$FEATUREPLANT_DB_PASSWORD}"
FRONTEND_ADAPTER_FEATURE_SOURCE_RAW="${FRONTEND_ADAPTER_FEATURE_SOURCE:-}"
FRONTEND_ADAPTER_FEATURE_SOURCE="${FRONTEND_ADAPTER_FEATURE_SOURCE_RAW:-latest_market_state}"
FRONTEND_ADAPTER_BASIC_AUTH_USER="${FRONTEND_ADAPTER_BASIC_AUTH_USER:-}"
FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD="${FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD:-}"
FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS="${FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS:-500}"
DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT="${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT:-${FRONTEND_ADAPTER_HOST_PORT:-8090}}"
FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT}}"
FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
DEMO_SYMBOL="${DEMO_SYMBOL:-DEMO-DBPRIMARY-26MAY19-T50}"
SMOKE_HTTP_ATTEMPTS="${SMOKE_HTTP_ATTEMPTS:-30}"
SMOKE_HTTP_RETRY_SLEEP_SECONDS="${SMOKE_HTTP_RETRY_SLEEP_SECONDS:-1}"
SMOKE_DB_ATTEMPTS="${SMOKE_DB_ATTEMPTS:-60}"
SMOKE_DB_RETRY_SLEEP_SECONDS="${SMOKE_DB_RETRY_SLEEP_SECONDS:-1}"
EXPECTED_FEATURE_OUTPUTS_MIN="${EXPECTED_FEATURE_OUTPUTS_MIN:-}"
EXPECTED_FEATURE_COUNT_MIN="${EXPECTED_FEATURE_COUNT_MIN:-}"
EXPECTED_HISTORY_BARS_MIN="${EXPECTED_HISTORY_BARS_MIN:-}"
EXPECTED_REFRESH_TOTAL_LOADED_MIN="${EXPECTED_REFRESH_TOTAL_LOADED_MIN:-}"
PRODUCT_DEMO_SEMANTIC_FIXTURE="${PRODUCT_DEMO_SEMANTIC_FIXTURE:-false}"
PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS="${PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS:-120}"
EXPECTED_SEMANTIC_METADATA_MIN_ROWS="${EXPECTED_SEMANTIC_METADATA_MIN_ROWS:-80}"
PRODUCT_DEMO_REPLAY_ID="demo-db-primary-long-replay"

case "$PRODUCT_DEMO_SCENARIO" in
    baseline|"")
        ;;
    long-replay)
        DEMO_SEED_SQL="${DEMO_SEED_SQL:-$SCRIPT_DIR/db-primary-demo-long-replay-seed.sql}"
        if [ -n "$FRONTEND_ADAPTER_FEATURE_SOURCE_RAW" ] \
            && [ "$FRONTEND_ADAPTER_FEATURE_SOURCE_RAW" != "feature_outputs" ]; then
            printf 'long-replay requires FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs, got %s\n' \
                "$FRONTEND_ADAPTER_FEATURE_SOURCE_RAW" >&2
            exit 2
        else
            FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs
        fi
        FEATUREPLANT_DB_INCLUDE_REPLAY=true
        FEATUREPLANT_DB_REPLAY_ID="$PRODUCT_DEMO_REPLAY_ID"
        DEMO_LIMIT="${DEMO_LIMIT:-120}"
        EXPECTED_FEATURE_COUNT_MIN="${EXPECTED_FEATURE_COUNT_MIN:-51}"
        EXPECTED_HISTORY_BARS_MIN="${EXPECTED_HISTORY_BARS_MIN:-51}"
        EXPECTED_REFRESH_TOTAL_LOADED_MIN="${EXPECTED_REFRESH_TOTAL_LOADED_MIN:-51}"
        ;;
    *)
        printf 'unsupported PRODUCT_DEMO_SCENARIO: %s\n' "$PRODUCT_DEMO_SCENARIO" >&2
        exit 2
        ;;
esac
EXPECTED_FEATURE_COUNT_MIN="${EXPECTED_FEATURE_COUNT_MIN:-1}"
EXPECTED_HISTORY_BARS_MIN="${EXPECTED_HISTORY_BARS_MIN:-1}"
EXPECTED_REFRESH_TOTAL_LOADED_MIN="${EXPECTED_REFRESH_TOTAL_LOADED_MIN:-1}"

export LOCAL_DB_NAME LOCAL_DB_USER LOCAL_DB_PASSWORD
export PRODUCT_DEMO_SCENARIO DEMO_SEED_SQL PRODUCT_DEMO_REPLAY_ID
export FEATUREPLANT_DB_URL FEATUREPLANT_DB_USER FEATUREPLANT_DB_PASSWORD
export FEATUREPLANT_DB_CURSOR_NAME FEATUREPLANT_DB_INCLUDE_REPLAY FEATUREPLANT_DB_REPLAY_ID
export FEATUREPLANT_STREAMS FEATUREPLANT_MODULES
export FEATUREPLANT_BATCH_SIZE FEATUREPLANT_IDLE_SLEEP_MS
export FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY
export FEATUREPLANT_DB_OUTPUT_BATCH_SIZE FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS
export FEATUREPLANT_METRICS_HOST FEATUREPLANT_METRICS_PORT FEATUREPLANT_METRICS_HOST_PORT
export FRONTEND_ADAPTER_DB_URL FRONTEND_ADAPTER_DB_USER FRONTEND_ADAPTER_DB_PASSWORD
export FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS
export FRONTEND_ADAPTER_BASIC_AUTH_USER FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD
export DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT
export PRODUCT_DEMO_SEMANTIC_FIXTURE PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS EXPECTED_SEMANTIC_METADATA_MIN_ROWS

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

truthy() {
    case "$1" in
        1|true|TRUE|yes|YES|on|ON)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

demo_feature_outputs() {
    psql_scalar "select count(*) from feature_outputs where source_event_id like 'demo-db-primary-canonical-%'"
}

demo_latest_market_state_count() {
    psql_scalar "select count(*) from latest_market_state where market_ticker like 'DEMO-DBPRIMARY-%'"
}

cursor_commit_seq() {
    cursor_name="$(sql_literal "$FEATUREPLANT_DB_CURSOR_NAME")"
    psql_scalar "select coalesce((select last_commit_seq from featureplant_cursors where cursor_name = '${cursor_name}'), 0)"
}

frontend_curl() {
    if [ -n "$FRONTEND_ADAPTER_BASIC_AUTH_USER" ] && [ -n "$FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD" ]; then
        curl --user "${FRONTEND_ADAPTER_BASIC_AUTH_USER}:${FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD}" "$@"
    else
        curl "$@"
    fi
}

wait_frontend_started() {
    health_file="$tmpdir/product-health.json"
    attempt=1
    while :; do
        if frontend_curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FRONTEND_BASE_URL}/health" -o "$health_file" \
            && python3 - "$health_file" "$FRONTEND_ADAPTER_FEATURE_SOURCE" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
if body.get("service") != "frontend-adapter":
    raise SystemExit("service mismatch")
expected_feature_source = sys.argv[2].strip().replace("-", "_")
if expected_feature_source == "latest_state":
    expected_feature_source = "latest_market_state"
if body.get("feature_source") != expected_feature_source:
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
print(body.get("feature_source") or "")
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

wait_featureplant_metrics_health() {
    health_file="$tmpdir/featureplant-health.txt"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FEATUREPLANT_METRICS_BASE_URL}/health" -o "$health_file" \
            && grep -q 'status ok' "$health_file"; then
            printf 'PASS featureplant_health url=%s/health\n' "$FEATUREPLANT_METRICS_BASE_URL"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'FeaturePlant metrics health endpoint did not become reachable at %s/health\n' \
                "$FEATUREPLANT_METRICS_BASE_URL" >&2
            print_product_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

wait_featureplant_metrics() {
    expected="$1"
    metrics_file="$tmpdir/featureplant.metrics"
    values_file="$tmpdir/featureplant.metrics.values"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FEATUREPLANT_METRICS_BASE_URL}/metrics" -o "$metrics_file" \
            && python3 - "$metrics_file" "$expected" > "$values_file" <<'PY'
import re
import sys

metrics_path = sys.argv[1]
expected = int(sys.argv[2])
with open(metrics_path, "r", encoding="utf-8") as handle:
    body = handle.read()

def metric_value(key):
    match = re.search(r"^" + re.escape(key) + r" (-?\d+)$", body, re.MULTILINE)
    if match is None:
        raise SystemExit(f"missing metric {key}")
    return int(match.group(1))

accepted = metric_value('featureplant_db_output_events_total{result="accepted",service="featureplant"}')
written = metric_value('featureplant_db_output_events_total{result="written",service="featureplant"}')
queue_depth = metric_value('featureplant_db_output_queue_depth{service="featureplant"}')
if accepted < expected:
    raise SystemExit(f"accepted {accepted} below expected {expected}")
if written < expected:
    raise SystemExit(f"written {written} below expected {expected}")
if queue_depth < 0:
    raise SystemExit(f"queue depth {queue_depth} is negative")
print(accepted)
print(written)
print(queue_depth)
PY
        then
            accepted="$(sed -n '1p' "$values_file")"
            written="$(sed -n '2p' "$values_file")"
            queue_depth="$(sed -n '3p' "$values_file")"
            printf 'PASS featureplant_metrics accepted=%s written=%s queue_depth=%s expected_written_at_least=%s\n' \
                "$accepted" "$written" "$queue_depth" "$expected"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'FeaturePlant metrics did not reach expected DB output counts at %s/metrics\n' \
                "$FEATUREPLANT_METRICS_BASE_URL" >&2
            print_product_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

wait_semantic_metadata_fixture() {
    sector_file="$tmpdir/semantic-fixture-sector.json"
    tag_file="$tmpdir/semantic-fixture-tag.json"
    markets_file="$tmpdir/semantic-fixture-markets.json"
    values_file="$tmpdir/semantic-fixture.values"
    attempt=1
    while :; do
        if frontend_curl -fsS --noproxy "$FRONTEND_NO_PROXY" \
                "${FRONTEND_BASE_URL}/api/semantic-metadata/treemap?group_by=sector&limit=200&status=generated&q=DEMO-SEMANTIC" \
                -o "$sector_file" \
            && frontend_curl -fsS --noproxy "$FRONTEND_NO_PROXY" \
                "${FRONTEND_BASE_URL}/api/semantic-metadata/treemap?group_by=tag&limit=200&status=generated&q=DEMO-SEMANTIC" \
                -o "$tag_file" \
            && frontend_curl -fsS --noproxy "$FRONTEND_NO_PROXY" \
                "${FRONTEND_BASE_URL}/api/semantic-metadata/markets?limit=200&status=generated&q=DEMO-SEMANTIC" \
                -o "$markets_file" \
            && python3 - "$sector_file" "$tag_file" "$markets_file" "$EXPECTED_SEMANTIC_METADATA_MIN_ROWS" \
                > "$values_file" <<'PY'
import json
import sys

sector_path, tag_path, markets_path, expected_raw = sys.argv[1:]
expected = int(expected_raw)

with open(sector_path, "r", encoding="utf-8") as handle:
    sector = json.load(handle)
with open(tag_path, "r", encoding="utf-8") as handle:
    tag = json.load(handle)
with open(markets_path, "r", encoding="utf-8") as handle:
    markets_body = json.load(handle)

def require(condition, message):
    if not condition:
        raise SystemExit(message)

require(sector.get("status") == "ok", f"sector treemap status {sector.get('status')}")
require(tag.get("status") == "ok", f"tag treemap status {tag.get('status')}")
require(markets_body.get("status") == "ok", f"markets status {markets_body.get('status')}")

sector_count = int(sector.get("count") or 0)
sector_groups = sector.get("groups") or []
tag_count = int(tag.get("count") or 0)
tag_groups = tag.get("groups") or []
markets = markets_body.get("markets") or []

require(sector_count >= expected, f"sector treemap count {sector_count} below expected {expected}")
require(tag_count >= expected, f"tag treemap count {tag_count} below expected {expected}")
require(len(sector_groups) >= 5, f"sector group count {len(sector_groups)} below 5")
require(any(group.get("leaves") for group in tag_groups), "tag treemap returned no leaves")
require(int(markets_body.get("count") or 0) >= expected, "markets count below expected")
require(markets, "markets endpoint returned no rows")

for group in sector_groups + tag_groups:
    for leaf in group.get("leaves") or []:
        ticker = str(leaf.get("market_ticker") or "")
        require(ticker.startswith("DEMO-SEMANTIC-"), f"fixture query returned non-fixture leaf {ticker}")

for market in markets:
    ticker = str(market.get("market_ticker") or "")
    metadata = market.get("semantic_metadata") or {}
    require(ticker.startswith("DEMO-SEMANTIC-"), f"fixture query returned non-fixture market {ticker}")
    require(metadata.get("status") == "generated", f"market {ticker} missing generated semantic metadata")
    require(metadata.get("sector"), f"market {ticker} missing sector")

print(sector_count)
print(len(sector_groups))
print(len(tag_groups))
print(len(markets))
PY
        then
            semantic_count="$(sed -n '1p' "$values_file")"
            sector_groups="$(sed -n '2p' "$values_file")"
            tag_groups="$(sed -n '3p' "$values_file")"
            markets_count="$(sed -n '4p' "$values_file")"
            printf 'PASS semantic_metadata_fixture rows=%s sector_groups=%s tag_groups=%s markets=%s expected_rows_at_least=%s\n' \
                "$semantic_count" "$sector_groups" "$tag_groups" "$markets_count" "$EXPECTED_SEMANTIC_METADATA_MIN_ROWS"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'Semantic metadata fixture did not become visible through frontend API at %s\n' \
                "$FRONTEND_BASE_URL" >&2
            print_product_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

docker compose --profile db-primary-product stop featureplant-db-follower >/dev/null 2>&1 || true
docker compose --profile db-primary-product rm -f featureplant-db-follower >/dev/null 2>&1 || true

"$SCRIPT_DIR/db-primary-demo-seed.sh"

if truthy "$PRODUCT_DEMO_SEMANTIC_FIXTURE"; then
    SEMANTIC_DEMO_RUN_MIGRATIONS=false \
    PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS="$PRODUCT_DEMO_SEMANTIC_FIXTURE_ROWS" \
    "$SCRIPT_DIR/semantic-metadata-demo-seed.sh"
fi

expected_commit_seq="$(
    psql_scalar "select coalesce(max(canonical_commit_seq), 0) from canonical_events where event_id like 'demo-db-primary-canonical-%'"
)"
if [ "$expected_commit_seq" -le 0 ]; then
    printf 'demo seed did not create canonical commit sequence rows\n' >&2
    exit 1
fi
cursor_before="$(cursor_commit_seq)"
if [ "$expected_commit_seq" -le "$cursor_before" ]; then
    printf 'demo seed target commit sequence is not ahead of FeaturePlant cursor: target=%s cursor_before=%s\n' \
        "$expected_commit_seq" "$cursor_before" >&2
    exit 1
fi
expected_feature_output_count="$(
    psql_scalar "select count(*) from canonical_events where event_id like 'demo-db-primary-canonical-%' and stream_name in ('canonical.trade','canonical.ticker','derived.top_of_book')"
)"
if [ -z "$EXPECTED_FEATURE_OUTPUTS_MIN" ]; then
    EXPECTED_FEATURE_OUTPUTS_MIN="$expected_feature_output_count"
fi

FEATUREPLANT_RUN_ONCE=false \
FRONTEND_ADAPTER_FEATURE_SOURCE="$FRONTEND_ADAPTER_FEATURE_SOURCE" \
FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_ENABLED=true \
FRONTEND_ADAPTER_METADATA_SOURCE=auto \
docker compose --profile db-primary-product up -d --build --force-recreate frontend-adapter-db-primary

frontend_health="$(wait_frontend_started)"
frontend_started_at="$(printf '%s\n' "$frontend_health" | sed -n '1p')"
frontend_feature_source="$(printf '%s\n' "$frontend_health" | sed -n '2p')"
frontend_initial_loaded="$(printf '%s\n' "$frontend_health" | sed -n '3p')"

FEATUREPLANT_SOURCE=db \
FEATUREPLANT_OUTPUT=db \
FEATUREPLANT_RUN_ONCE=false \
FEATUREPLANT_DB_INCLUDE_REPLAY="$FEATUREPLANT_DB_INCLUDE_REPLAY" \
FEATUREPLANT_DB_REPLAY_ID="$FEATUREPLANT_DB_REPLAY_ID" \
FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED="$FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED" \
FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY="$FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY" \
FEATUREPLANT_DB_OUTPUT_BATCH_SIZE="$FEATUREPLANT_DB_OUTPUT_BATCH_SIZE" \
FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS="$FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS" \
FEATUREPLANT_METRICS_HOST="$FEATUREPLANT_METRICS_HOST" \
FEATUREPLANT_METRICS_PORT="$FEATUREPLANT_METRICS_PORT" \
FEATUREPLANT_METRICS_HOST_PORT="$FEATUREPLANT_METRICS_HOST_PORT" \
docker compose --profile db-primary-product up -d --build --force-recreate featureplant-db-follower

wait_featureplant_metrics_health

follow_result="$(wait_featureplant_followed_seed "$expected_commit_seq")"
feature_outputs_after="$(printf '%s\n' "$follow_result" | sed -n '1p')"
cursor_after="$(printf '%s\n' "$follow_result" | sed -n '2p')"

if [ "$PRODUCT_DEMO_SCENARIO" = "long-replay" ]; then
    latest_state_count="$(demo_latest_market_state_count)"
    if [ "$latest_state_count" -ne 0 ]; then
        printf 'long-replay must not update latest_market_state: latest_market_state=%s\n' \
            "$latest_state_count" >&2
        exit 1
    fi
fi

if truthy "$PRODUCT_DEMO_SEMANTIC_FIXTURE"; then
    wait_semantic_metadata_fixture
fi

wait_featureplant_metrics "$EXPECTED_FEATURE_OUTPUTS_MIN"

FRONTEND_BASE_URL="$FRONTEND_BASE_URL" \
EXPECTED_FRONTEND_STARTED_AT="$frontend_started_at" \
EXPECTED_REFRESH_TOTAL_LOADED_MIN="$EXPECTED_REFRESH_TOTAL_LOADED_MIN" \
EXPECTED_FEATURE_COUNT_MIN="$EXPECTED_FEATURE_COUNT_MIN" \
EXPECTED_HISTORY_BARS_MIN="$EXPECTED_HISTORY_BARS_MIN" \
EXPECTED_FEATURE_SOURCE="$FRONTEND_ADAPTER_FEATURE_SOURCE" \
FRONTEND_ADAPTER_BASIC_AUTH_USER="$FRONTEND_ADAPTER_BASIC_AUTH_USER" \
FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD="$FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD" \
DEMO_SYMBOL="$DEMO_SYMBOL" \
DEMO_LIMIT="$DEMO_LIMIT" \
SMOKE_HTTP_ATTEMPTS="$SMOKE_HTTP_ATTEMPTS" \
SMOKE_HTTP_RETRY_SLEEP_SECONDS="$SMOKE_HTTP_RETRY_SLEEP_SECONDS" \
"$SCRIPT_DIR/db-primary-demo-smoke.sh"

printf 'PASS db_primary_product_smoke scenario=%s symbol=%s feature_source=%s expected_feature_source=%s frontend_started_at=%s initial_loaded=%s feature_outputs=%s expected_feature_outputs_at_least=%s cursor=%s expected_cursor_at_least=%s\n' \
    "$PRODUCT_DEMO_SCENARIO" "$DEMO_SYMBOL" "$frontend_feature_source" "$FRONTEND_ADAPTER_FEATURE_SOURCE" "$frontend_started_at" "$frontend_initial_loaded" \
    "$feature_outputs_after" "$EXPECTED_FEATURE_OUTPUTS_MIN" "$cursor_after" "$expected_commit_seq"
printf 'PASS db_primary_product_cursor cursor_before=%s cursor_after=%s expected_cursor_at_least=%s\n' \
    "$cursor_before" "$cursor_after" "$expected_commit_seq"
