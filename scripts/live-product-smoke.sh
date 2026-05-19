#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT"

COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env}"
COMPOSE_PROFILE="${COMPOSE_PROFILE:-live-product}"
LIVE_PRODUCT_SMOKE_DOCKER_SUDO="${LIVE_PRODUCT_SMOKE_DOCKER_SUDO:-false}"
FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
SMOKE_HTTP_ATTEMPTS="${SMOKE_HTTP_ATTEMPTS:-45}"
SMOKE_HTTP_RETRY_SLEEP_SECONDS="${SMOKE_HTTP_RETRY_SLEEP_SECONDS:-1}"
SMOKE_DB_ATTEMPTS="${SMOKE_DB_ATTEMPTS:-90}"
SMOKE_DB_RETRY_SLEEP_SECONDS="${SMOKE_DB_RETRY_SLEEP_SECONDS:-1}"
LIVE_PRODUCT_SMOKE_REQUIRE_LIVE_DATA="${LIVE_PRODUCT_SMOKE_REQUIRE_LIVE_DATA:-false}"

env_file_value() {
    key="$1"
    if [ ! -f "$COMPOSE_ENV_FILE" ]; then
        return 1
    fi
    awk -v key="$key" '
        index($0, key "=") == 1 {
            sub(/^[^=]*=/, "")
            value = $0
            found = 1
        }
        END {
            if (!found) {
                exit 1
            }
            print value
        }
    ' "$COMPOSE_ENV_FILE"
}

env_or_file() {
    name="$1"
    default_value="$2"
    eval "current_value=\${$name:-}"
    if [ -n "$current_value" ]; then
        printf '%s' "$current_value"
        return 0
    fi
    file_value="$(env_file_value "$name" 2>/dev/null || true)"
    if [ -n "$file_value" ]; then
        printf '%s' "$file_value"
    else
        printf '%s' "$default_value"
    fi
}

LOCAL_DB_NAME="$(env_or_file LOCAL_DB_NAME kalshi_test)"
LOCAL_DB_USER="$(env_or_file LOCAL_DB_USER kalshi)"
LOCAL_DB_PASSWORD="$(env_or_file LOCAL_DB_PASSWORD kalshi)"
FEATUREPLANT_DB_CURSOR_NAME="$(env_or_file FEATUREPLANT_DB_CURSOR_NAME db-primary-product-featureplant)"
WSCLIENT_METRICS_HOST_PORT="$(env_or_file WSCLIENT_METRICS_HOST_PORT 8091)"
STREAM_TAP_HOST_PORT="$(env_or_file STREAM_TAP_HOST_PORT 8080)"
FEATUREPLANT_METRICS_HOST_PORT="$(env_or_file FEATUREPLANT_METRICS_HOST_PORT 8094)"
DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT="$(env_or_file DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT 8090)"

WSCLIENT_HEALTH_URL="${WSCLIENT_HEALTH_URL:-http://127.0.0.1:${WSCLIENT_METRICS_HOST_PORT}/health}"
STREAM_TAP_HEALTH_URL="${STREAM_TAP_HEALTH_URL:-http://127.0.0.1:${STREAM_TAP_HOST_PORT}/health}"
FEATUREPLANT_HEALTH_URL="${FEATUREPLANT_HEALTH_URL:-http://127.0.0.1:${FEATUREPLANT_METRICS_HOST_PORT}/health}"
FEATUREPLANT_METRICS_URL="${FEATUREPLANT_METRICS_URL:-http://127.0.0.1:${FEATUREPLANT_METRICS_HOST_PORT}/metrics}"
FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT}}"
FRONTEND_HEALTH_URL="${FRONTEND_HEALTH_URL:-${FRONTEND_BASE_URL}/health}"

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/live-product-smoke.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT INT HUP TERM

is_true() {
    case "$1" in
        true|TRUE|True|1|yes|YES|Yes) return 0 ;;
        *) return 1 ;;
    esac
}

docker_compose() {
    if is_true "$LIVE_PRODUCT_SMOKE_DOCKER_SUDO"; then
        sudo docker compose "$@"
    else
        docker compose "$@"
    fi
}

compose() {
    if [ -f "$COMPOSE_ENV_FILE" ]; then
        docker_compose --env-file "$COMPOSE_ENV_FILE" --profile "$COMPOSE_PROFILE" "$@"
    else
        docker_compose --profile "$COMPOSE_PROFILE" "$@"
    fi
}

print_diagnostics() {
    printf '\nDocker Compose status:\n' >&2
    compose ps >&2 || true
    printf '\nRecent live-product logs:\n' >&2
    compose logs --tail=120 \
        wsclient streamtap featureplant-db-follower frontend-adapter-db-primary >&2 || true
}

sql_literal() {
    printf '%s' "$1" | sed "s/'/''/g"
}

psql_scalar() {
    compose exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 -c "$1" \
        | tr -d '\r'
}

urlencode() {
    python3 - "$1" <<'PY'
import sys
from urllib.parse import quote
print(quote(sys.argv[1], safe=""))
PY
}

wait_plain_health() {
    service="$1"
    url="$2"
    output="$tmpdir/${service}.health.txt"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" "$url" -o "$output" \
            && grep -q 'status ok' "$output"; then
            printf 'PASS health service=%s url=%s\n' "$service" "$url"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'health check failed: service=%s url=%s attempts=%s\n' "$service" "$url" "$attempt" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

wait_featureplant_metrics() {
    expected="$1"
    output="$tmpdir/featureplant.metrics"
    values="$tmpdir/featureplant.metrics.values"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" "$FEATUREPLANT_METRICS_URL" -o "$output" \
            && python3 - "$output" "$expected" > "$values" <<'PY'
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
            accepted="$(sed -n '1p' "$values")"
            written="$(sed -n '2p' "$values")"
            queue_depth="$(sed -n '3p' "$values")"
            printf 'PASS featureplant_metrics url=%s accepted=%s written=%s queue_depth=%s expected_written_at_least=%s\n' \
                "$FEATUREPLANT_METRICS_URL" "$accepted" "$written" "$queue_depth" "$expected"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'FeaturePlant metrics did not reach expected DB output counts at %s\n' \
                "$FEATUREPLANT_METRICS_URL" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

wait_streamtap_health() {
    output="$tmpdir/streamtap.health.json"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" "$STREAM_TAP_HEALTH_URL" -o "$output" \
            && python3 - "$output" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
if body.get("status") != "ok":
    raise SystemExit("streamtap status is not ok")
if not isinstance(body.get("streams"), dict):
    raise SystemExit("streamtap streams are missing")
PY
        then
            printf 'PASS health service=streamtap url=%s\n' "$STREAM_TAP_HEALTH_URL"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'health check failed: service=streamtap url=%s attempts=%s\n' "$STREAM_TAP_HEALTH_URL" "$attempt" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

wait_frontend_ready() {
    output="$tmpdir/frontend.health.json"
    selection="$tmpdir/frontend.health.txt"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" "$FRONTEND_HEALTH_URL" -o "$output" \
            && python3 - "$output" > "$selection" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
if body.get("service") != "frontend-adapter":
    raise SystemExit("frontend service mismatch")
if body.get("feature_source") != "feature_outputs":
    raise SystemExit("frontend is not using feature_outputs")
refresh = body.get("feature_output_refresh")
if not isinstance(refresh, dict):
    raise SystemExit("feature output refresh status missing")
if refresh.get("enabled") is not True or refresh.get("running") is not True:
    raise SystemExit("feature output refresh is not running")
total_loaded = refresh.get("total_loaded")
refresh_errors = refresh.get("refresh_errors")
if isinstance(total_loaded, bool) or not isinstance(total_loaded, int):
    raise SystemExit("feature output refresh total_loaded is not an integer")
if isinstance(refresh_errors, bool) or not isinstance(refresh_errors, int):
    raise SystemExit("feature output refresh errors is not an integer")
print(body.get("started_at", ""))
print(total_loaded)
print(refresh_errors)
PY
        then
            cat "$selection"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'frontend health did not become ready at %s\n' "$FRONTEND_HEALTH_URL" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

cursor_commit_seq() {
    cursor_name="$(sql_literal "$FEATUREPLANT_DB_CURSOR_NAME")"
    psql_scalar "select coalesce((select last_commit_seq from featureplant_cursors where cursor_name = '${cursor_name}'), 0)"
}

seed_canonical_events() {
    run_id_sql="$(sql_literal "$1")"
    market_sql="$(sql_literal "$2")"
    prefix_sql="$(sql_literal "$3")"
    psql_scalar "
with seed_clock as (
    select floor(extract(epoch from now()) * 1000)::bigint as base_ts_ms
),
seed_rows(stream_name, event_type, event_id, offset_ms, payload) as (
    values
    (
        'derived.top_of_book',
        'top_of_book_update',
        '${prefix_sql}-bbo-001',
        1,
        jsonb_build_object(
            'event_id', '${prefix_sql}-bbo-001',
            'event_type', 'top_of_book_update',
            'schema_version', 1,
            'stream_name', 'derived.top_of_book',
            'metadata', jsonb_build_object(
                'source', 'live_product_smoke',
                'market_ticker', '${market_sql}',
                'event_ts_ms', (select base_ts_ms + 1 from seed_clock)
            ),
            'bid_price_micros', 451000,
            'ask_price_micros', 472000,
            'bid_quantity_micros', 1200000,
            'ask_quantity_micros', 1000000,
            'crossed', false,
            'smoke_run_id', '${run_id_sql}'
        )
    ),
    (
        'canonical.ticker',
        'ticker_update',
        '${prefix_sql}-ticker-001',
        2,
        jsonb_build_object(
            'event_id', '${prefix_sql}-ticker-001',
            'event_type', 'ticker_update',
            'schema_version', 1,
            'stream_name', 'canonical.ticker',
            'metadata', jsonb_build_object(
                'source', 'live_product_smoke',
                'market_ticker', '${market_sql}',
                'event_ts_ms', (select base_ts_ms + 2 from seed_clock)
            ),
            'price_micros', 462000,
            'yes_bid_micros', 451000,
            'yes_ask_micros', 472000,
            'volume_micros', 31000000,
            'smoke_run_id', '${run_id_sql}'
        )
    ),
    (
        'canonical.trade',
        'market_trade',
        '${prefix_sql}-trade-001',
        3,
        jsonb_build_object(
            'event_id', '${prefix_sql}-trade-001',
            'event_type', 'market_trade',
            'schema_version', 1,
            'stream_name', 'canonical.trade',
            'metadata', jsonb_build_object(
                'source', 'live_product_smoke',
                'market_ticker', '${market_sql}',
                'event_ts_ms', (select base_ts_ms + 3 from seed_clock)
            ),
            'trade_id', '${prefix_sql}-trade-001',
            'yes_price_micros', 462000,
            'no_price_micros', 538000,
            'quantity_micros', 2100000,
            'taker_side', 'yes',
            'smoke_run_id', '${run_id_sql}'
        )
    )
),
metadata_insert as (
    insert into market_metadata (
        market_ticker,
        event_ticker,
        series_ticker,
        status,
        market_payload
    )
    values (
        '${market_sql}',
        'LIVE-PRODUCT-SMOKE',
        'LIVE-PRODUCT-SMOKE',
        'open',
        jsonb_build_object(
            'market_ticker', '${market_sql}',
            'event_ticker', 'LIVE-PRODUCT-SMOKE',
            'series_ticker', 'LIVE-PRODUCT-SMOKE',
            'status', 'open',
            'smoke_run_id', '${run_id_sql}'
        )
    )
    on conflict (market_ticker) do update
        set updated_at = now(),
            market_payload = excluded.market_payload
),
inserted as (
    insert into canonical_events (
        event_id,
        stream_name,
        event_type,
        schema_version,
        market_ticker,
        event_ts_ms,
        payload
    )
    select
        seed_rows.event_id,
        seed_rows.stream_name,
        seed_rows.event_type,
        1,
        '${market_sql}',
        seed_clock.base_ts_ms + seed_rows.offset_ms,
        seed_rows.payload
    from seed_rows
    cross join seed_clock
    on conflict do nothing
    returning canonical_commit_seq
)
select count(*), coalesce(max(canonical_commit_seq), 0) from inserted;"
}

wait_featureplant_followed_seed() {
    prefix_sql="$(sql_literal "$1")"
    expected_commit_seq="$2"
    attempt=1
    while :; do
        output_count="$(psql_scalar "select count(*) from feature_outputs where source_event_id like '${prefix_sql}-%'")"
        current_cursor_seq="$(cursor_commit_seq)"
        if [ "$output_count" -ge 3 ] && [ "$current_cursor_seq" -ge "$expected_commit_seq" ]; then
            printf '%s\n%s\n' "$output_count" "$current_cursor_seq"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_DB_ATTEMPTS" ]; then
            printf 'FeaturePlant did not write smoke feature_outputs: prefix=%s feature_outputs=%s cursor=%s expected_cursor_at_least=%s\n' \
                "$1" "$output_count" "$current_cursor_seq" "$expected_commit_seq" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_DB_RETRY_SLEEP_SECONDS"
    done
}

wait_frontend_refresh_progress() {
    expected_started_at="$1"
    min_total_loaded="$2"
    max_refresh_errors="$3"
    attempt=1
    while :; do
        health="$(wait_frontend_ready)"
        started_at="$(printf '%s\n' "$health" | sed -n '1p')"
        total_loaded="$(printf '%s\n' "$health" | sed -n '2p')"
        refresh_errors="$(printf '%s\n' "$health" | sed -n '3p')"
        if [ "$started_at" = "$expected_started_at" ] \
            && [ "$total_loaded" -gt "$min_total_loaded" ] \
            && [ "$refresh_errors" -le "$max_refresh_errors" ]; then
            printf '%s\n%s\n%s\n' "$started_at" "$total_loaded" "$refresh_errors"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'frontend refresh did not load smoke outputs without restart: started_at=%s expected=%s total_loaded=%s before=%s refresh_errors=%s before=%s\n' \
                "$started_at" "$expected_started_at" "$total_loaded" "$min_total_loaded" "$refresh_errors" "$max_refresh_errors" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

check_product_static_ui() {
    index_file="$tmpdir/frontend-index.html"
    app_file="$tmpdir/frontend-app.js"
    css_file="$tmpdir/frontend-styles.css"
    curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FRONTEND_BASE_URL}/" -o "$index_file"
    curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FRONTEND_BASE_URL}/app.js" -o "$app_file"
    curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FRONTEND_BASE_URL}/styles.css" -o "$css_file"
    grep -q 'Kalshi Frontend Adapter Demo' "$index_file"
    grep -q 'same origin' "$index_file"
    grep -q '/quotes/updates' "$app_file"
    grep -q 'chart-container' "$css_file"
    printf 'PASS frontend_static_ui url=%s/\n' "$FRONTEND_BASE_URL"
}

wait_frontend_feature_output() {
    market="$1"
    source_event_id="$2"
    encoded_market="$(urlencode "$market")"
    encoded_feature="$(urlencode feature.bbo)"
    output="$tmpdir/frontend.features.json"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" \
            "${FRONTEND_BASE_URL}/features?symbol=${encoded_market}&feature=${encoded_feature}&limit=20" \
            -o "$output" \
            && python3 - "$output" "$market" "$source_event_id" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
market = sys.argv[2]
source_event_id = sys.argv[3]
if body.get("symbol") != market or body.get("feature") != "feature.bbo":
    raise SystemExit("feature response metadata mismatch")
outputs = body.get("outputs")
if not isinstance(outputs, list):
    raise SystemExit("feature outputs missing")
for output in outputs:
    if isinstance(output, dict) and output.get("source_event_id") == source_event_id:
        values = output.get("values")
        if not isinstance(values, dict):
            raise SystemExit("seeded feature has no values")
        if values.get("midpoint_micros") != 461500:
            raise SystemExit("seeded feature midpoint mismatch")
        raise SystemExit(0)
raise SystemExit("seeded feature output not visible")
PY
        then
            printf 'PASS frontend_feature_outputs market=%s feature=feature.bbo source_event_id=%s\n' \
                "$market" "$source_event_id"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'frontend did not expose seeded feature output: market=%s source_event_id=%s\n' \
                "$market" "$source_event_id" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

wait_frontend_quote() {
    market="$1"
    encoded_market="$(urlencode "$market")"
    output="$tmpdir/frontend.quotes.json"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" \
            "${FRONTEND_BASE_URL}/quotes?symbols=${encoded_market}" \
            -o "$output" \
            && python3 - "$output" "$market" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
market = sys.argv[2]
quotes = body.get("quotes")
if not isinstance(quotes, list):
    raise SystemExit("quotes missing")
for quote in quotes:
    if isinstance(quote, dict) and quote.get("symbol") == market:
        if quote.get("midpoint_micros") == 461500:
            raise SystemExit(0)
raise SystemExit("seeded quote not visible")
PY
        then
            printf 'PASS frontend_quotes market=%s midpoint_micros=461500\n' "$market"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'frontend quote did not reflect seeded BBO: market=%s\n' "$market" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

check_optional_live_data() {
    if ! is_true "$LIVE_PRODUCT_SMOKE_REQUIRE_LIVE_DATA"; then
        return 0
    fi
    live_count="$(psql_scalar "select count(*) from canonical_events where event_id not like 'live-product-smoke-%' and created_at >= now() - interval '15 minutes'")"
    if [ "$live_count" -le 0 ]; then
        printf 'live data requirement failed: no non-smoke canonical_events in the last 15 minutes\n' >&2
        print_diagnostics
        return 1
    fi
    printf 'PASS live_data recent_non_smoke_canonical_events=%s\n' "$live_count"
}

compose ps >/dev/null

wait_plain_health wsclient "$WSCLIENT_HEALTH_URL"
wait_streamtap_health
wait_plain_health featureplant-db-follower "$FEATUREPLANT_HEALTH_URL"
frontend_before="$(wait_frontend_ready)"
frontend_started_at_before="$(printf '%s\n' "$frontend_before" | sed -n '1p')"
frontend_loaded_before="$(printf '%s\n' "$frontend_before" | sed -n '2p')"
frontend_errors_before="$(printf '%s\n' "$frontend_before" | sed -n '3p')"
printf 'PASS health service=frontend-adapter url=%s started_at=%s feature_output_refresh_total_loaded=%s refresh_errors=%s\n' \
    "$FRONTEND_HEALTH_URL" "$frontend_started_at_before" "$frontend_loaded_before" "$frontend_errors_before"
check_product_static_ui

cursor_before="$(cursor_commit_seq)"
max_commit_before="$(psql_scalar "select coalesce(max(canonical_commit_seq), 0) from canonical_events")"
if [ "$cursor_before" -gt "$max_commit_before" ]; then
    printf 'FeaturePlant cursor is ahead of canonical_events: cursor=%s max_commit_seq=%s\n' \
        "$cursor_before" "$max_commit_before" >&2
    exit 1
fi

run_id="${LIVE_PRODUCT_SMOKE_RUN_ID:-$(date -u +%Y%m%d%H%M%S)-$$}"
case "$run_id" in
    *[!A-Za-z0-9_.-]*)
        printf 'LIVE_PRODUCT_SMOKE_RUN_ID contains unsupported characters: %s\n' "$run_id" >&2
        exit 1
        ;;
esac
seed_prefix="live-product-smoke-${run_id}"
market_ticker="${LIVE_PRODUCT_SMOKE_MARKET_TICKER:-LIVE-PRODUCT-SMOKE-${run_id}}"
bbo_event_id="${seed_prefix}-bbo-001"

seed_result="$(seed_canonical_events "$run_id" "$market_ticker" "$seed_prefix")"
seeded_count="$(printf '%s\n' "$seed_result" | awk -F '|' 'NR == 1 {print $1}')"
target_commit_seq="$(printf '%s\n' "$seed_result" | awk -F '|' 'NR == 1 {print $2}')"
if [ "$seeded_count" -ne 3 ] || [ "$target_commit_seq" -le "$cursor_before" ]; then
    printf 'smoke seed did not append three canonical rows after cursor: seeded=%s target_commit_seq=%s cursor_before=%s\n' \
        "$seeded_count" "$target_commit_seq" "$cursor_before" >&2
    exit 1
fi
printf 'PASS live_product_seed market=%s prefix=%s seeded_canonical_events=%s cursor_before=%s target_commit_seq=%s\n' \
    "$market_ticker" "$seed_prefix" "$seeded_count" "$cursor_before" "$target_commit_seq"

follow_result="$(wait_featureplant_followed_seed "$seed_prefix" "$target_commit_seq")"
feature_outputs_after="$(printf '%s\n' "$follow_result" | sed -n '1p')"
cursor_after="$(printf '%s\n' "$follow_result" | sed -n '2p')"
printf 'PASS featureplant_followed_seed prefix=%s feature_outputs=%s cursor_after=%s expected_cursor_at_least=%s\n' \
    "$seed_prefix" "$feature_outputs_after" "$cursor_after" "$target_commit_seq"
wait_featureplant_metrics "$feature_outputs_after"

frontend_after="$(wait_frontend_refresh_progress "$frontend_started_at_before" "$frontend_loaded_before" "$frontend_errors_before")"
frontend_started_at_after="$(printf '%s\n' "$frontend_after" | sed -n '1p')"
frontend_loaded_after="$(printf '%s\n' "$frontend_after" | sed -n '2p')"
frontend_errors_after="$(printf '%s\n' "$frontend_after" | sed -n '3p')"
wait_frontend_feature_output "$market_ticker" "$bbo_event_id"
wait_frontend_quote "$market_ticker"
check_optional_live_data

wait_plain_health wsclient "$WSCLIENT_HEALTH_URL"
wait_streamtap_health
wait_plain_health featureplant-db-follower "$FEATUREPLANT_HEALTH_URL"

printf 'PASS live_product_smoke market=%s run_id=%s cursor_before=%s target_commit_seq=%s cursor_after=%s feature_outputs=%s frontend_started_at=%s frontend_total_loaded_before=%s frontend_total_loaded_after=%s frontend_refresh_errors_after=%s\n' \
    "$market_ticker" "$run_id" "$cursor_before" "$target_commit_seq" "$cursor_after" "$feature_outputs_after" \
    "$frontend_started_at_after" "$frontend_loaded_before" "$frontend_loaded_after" "$frontend_errors_after"
