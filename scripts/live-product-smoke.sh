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
LIVE_PRODUCT_RELIABILITY_WINDOW_SECONDS="${LIVE_PRODUCT_RELIABILITY_WINDOW_SECONDS:-300}"
LIVE_PRODUCT_RELIABILITY_ROW_LIMIT="${LIVE_PRODUCT_RELIABILITY_ROW_LIMIT:-1000}"
LIVE_PRODUCT_BROWSER_SMOKE_ENABLED="${LIVE_PRODUCT_BROWSER_SMOKE_ENABLED:-false}"
FRONTEND_BROWSER_SMOKE_SCRIPT="${FRONTEND_BROWSER_SMOKE_SCRIPT:-scripts/frontend-product-browser-smoke.sh}"
EXPECTED_KALSHI_RELEASE_SHA="${EXPECTED_KALSHI_RELEASE_SHA:-}"
EXPECTED_KALSHI_APP_IMAGE="${EXPECTED_KALSHI_APP_IMAGE:-}"
EXPECTED_KALSHI_DEPLOY_PROFILE="${EXPECTED_KALSHI_DEPLOY_PROFILE:-}"
EXPECTED_KALSHI_GITHUB_RUN_ID="${EXPECTED_KALSHI_GITHUB_RUN_ID:-}"
EXPECTED_KALSHI_GITHUB_RUN_ATTEMPT="${EXPECTED_KALSHI_GITHUB_RUN_ATTEMPT:-}"

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

FEATUREPLANT_DB_CURSOR_NAME="$(env_or_file FEATUREPLANT_DB_CURSOR_NAME db-primary-product-featureplant)"
WSCLIENT_METRICS_HOST_PORT="$(env_or_file WSCLIENT_METRICS_HOST_PORT 8091)"
STREAM_TAP_HOST_PORT="$(env_or_file STREAM_TAP_HOST_PORT 8080)"
FEATUREPLANT_METRICS_HOST_PORT="$(env_or_file FEATUREPLANT_METRICS_HOST_PORT 8094)"
DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT="$(env_or_file DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT 8090)"
DB_WRITER_DATABASE_URL="$(env_or_file DB_WRITER_DATABASE_URL "")"
DB_WRITER_DATABASE_USER="$(env_or_file DB_WRITER_DATABASE_USER "")"
DB_WRITER_DATABASE_PASSWORD="$(env_or_file DB_WRITER_DATABASE_PASSWORD "")"
FEATUREPLANT_DB_URL="$(env_or_file FEATUREPLANT_DB_URL "$DB_WRITER_DATABASE_URL")"
FEATUREPLANT_DB_USER="$(env_or_file FEATUREPLANT_DB_USER "$DB_WRITER_DATABASE_USER")"
FEATUREPLANT_DB_PASSWORD="$(env_or_file FEATUREPLANT_DB_PASSWORD "$DB_WRITER_DATABASE_PASSWORD")"
FRONTEND_ADAPTER_DB_URL="$(env_or_file FRONTEND_ADAPTER_DB_URL "$DB_WRITER_DATABASE_URL")"
FRONTEND_ADAPTER_DB_USER="$(env_or_file FRONTEND_ADAPTER_DB_USER "$DB_WRITER_DATABASE_USER")"
FRONTEND_ADAPTER_DB_PASSWORD="$(env_or_file FRONTEND_ADAPTER_DB_PASSWORD "$DB_WRITER_DATABASE_PASSWORD")"
LIVE_PRODUCT_SMOKE_DB_URL="$(env_or_file LIVE_PRODUCT_SMOKE_DB_URL "$DB_WRITER_DATABASE_URL")"
LIVE_PRODUCT_SMOKE_DB_USER="$(env_or_file LIVE_PRODUCT_SMOKE_DB_USER "$DB_WRITER_DATABASE_USER")"
LIVE_PRODUCT_SMOKE_DB_PASSWORD="$(env_or_file LIVE_PRODUCT_SMOKE_DB_PASSWORD "$DB_WRITER_DATABASE_PASSWORD")"

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

urlencode() {
    python3 - "$1" <<'PY'
import sys
from urllib.parse import quote
print(quote(sys.argv[1], safe=""))
PY
}

fetch_sse_stream() {
    endpoint="$1"
    output="$2"
    error_output="${output}.err"
    set +e
    curl -fsS -N --max-time 3 --noproxy "$FRONTEND_NO_PROXY" \
        "${FRONTEND_BASE_URL}${endpoint}" \
        -o "$output" \
        2> "$error_output"
    status=$?
    set -e
    if [ "$status" -ne 0 ] && [ "$status" -ne 28 ]; then
        cat "$error_output" >&2 || true
        return 1
    fi
    if [ ! -s "$output" ]; then
        cat "$error_output" >&2 || true
        return 1
    fi
}

require_same_db_value() {
    label="$1"
    expected="$2"
    actual="$3"
    if [ -n "$actual" ] && [ "$actual" != "$expected" ]; then
        if [ "$label" = "password" ]; then
            printf 'live-product smoke DB password does not match deployed service DB password\n' >&2
        else
            printf 'live-product smoke DB %s mismatch: smoke=%s service=%s\n' "$label" "$expected" "$actual" >&2
        fi
        exit 1
    fi
}

validate_live_db_config() {
    if [ -z "$LIVE_PRODUCT_SMOKE_DB_URL" ]; then
        printf 'LIVE_PRODUCT_SMOKE_DB_URL/DB_WRITER_DATABASE_URL must point at the live DB\n' >&2
        exit 1
    fi
    require_same_db_value "url" "$LIVE_PRODUCT_SMOKE_DB_URL" "$DB_WRITER_DATABASE_URL"
    require_same_db_value "url" "$LIVE_PRODUCT_SMOKE_DB_URL" "$FEATUREPLANT_DB_URL"
    require_same_db_value "url" "$LIVE_PRODUCT_SMOKE_DB_URL" "$FRONTEND_ADAPTER_DB_URL"
    require_same_db_value "user" "$LIVE_PRODUCT_SMOKE_DB_USER" "$DB_WRITER_DATABASE_USER"
    require_same_db_value "user" "$LIVE_PRODUCT_SMOKE_DB_USER" "$FEATUREPLANT_DB_USER"
    require_same_db_value "user" "$LIVE_PRODUCT_SMOKE_DB_USER" "$FRONTEND_ADAPTER_DB_USER"
    require_same_db_value "password" "$LIVE_PRODUCT_SMOKE_DB_PASSWORD" "$DB_WRITER_DATABASE_PASSWORD"
    require_same_db_value "password" "$LIVE_PRODUCT_SMOKE_DB_PASSWORD" "$FEATUREPLANT_DB_PASSWORD"
    require_same_db_value "password" "$LIVE_PRODUCT_SMOKE_DB_PASSWORD" "$FRONTEND_ADAPTER_DB_PASSWORD"
}

db_probe() {
    db_probe_output="$tmpdir/db-probe.out"
    if ! compose run --rm --no-deps -T \
        -e "LIVE_PRODUCT_SMOKE_DB_URL=$LIVE_PRODUCT_SMOKE_DB_URL" \
        -e "LIVE_PRODUCT_SMOKE_DB_USER=$LIVE_PRODUCT_SMOKE_DB_USER" \
        -e "LIVE_PRODUCT_SMOKE_DB_PASSWORD=$LIVE_PRODUCT_SMOKE_DB_PASSWORD" \
        wsclient java -cp /app/app.jar edu.illinois.group8.storage.db.LiveProductSmokeDbProbeCli "$@" \
        > "$db_probe_output"; then
        cat "$db_probe_output" >&2
        return 1
    fi
    tr -d '\r' < "$db_probe_output"
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
            && python3 - "$output" \
                "$EXPECTED_KALSHI_RELEASE_SHA" \
                "$EXPECTED_KALSHI_APP_IMAGE" \
                "$EXPECTED_KALSHI_DEPLOY_PROFILE" \
                "$EXPECTED_KALSHI_GITHUB_RUN_ID" \
                "$EXPECTED_KALSHI_GITHUB_RUN_ATTEMPT" > "$selection" <<'PY'
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
release = body.get("release")
if not isinstance(release, dict):
    raise SystemExit("health check failed: release is missing")
for field, expected_index in (
    ("sha", 2),
    ("image", 3),
    ("profile", 4),
    ("run_id", 5),
    ("run_attempt", 6),
):
    if field not in release:
        raise SystemExit(f"frontend release.{field} is missing")
    expected_value = sys.argv[expected_index].strip() if len(sys.argv) > expected_index else ""
    actual_value = release.get(field)
    if expected_value and actual_value != expected_value:
        raise SystemExit(
            f"frontend release.{field} is {actual_value!r}, expected {expected_value!r}"
        )
freshness = body.get("data_freshness")
if not isinstance(freshness, dict):
    raise SystemExit("health check failed: data_freshness is missing")
for key in ("latest_event_ts_ms", "latest_event_age_ms", "symbol", "feature_name", "source_event_id", "store_sequence"):
    if key not in freshness:
        raise SystemExit(f"frontend data_freshness.{key} is missing")
readiness = body.get("product_readiness")
if not isinstance(readiness, dict):
    raise SystemExit("health check failed: product_readiness is missing")
readiness_status = readiness.get("status")
if readiness_status not in ("ok", "stale", "degraded"):
    raise SystemExit(f"frontend product_readiness.status is invalid: {readiness_status!r}")
if not isinstance(readiness.get("stale"), bool):
    raise SystemExit("frontend product_readiness.stale is not a boolean")
if not isinstance(readiness.get("degraded"), bool):
    raise SystemExit("frontend product_readiness.degraded is not a boolean")
if not isinstance(readiness.get("reasons"), list):
    raise SystemExit("frontend product_readiness.reasons is not a list")
if isinstance(readiness.get("stale_after_ms"), bool) or not isinstance(readiness.get("stale_after_ms"), int):
    raise SystemExit("frontend product_readiness.stale_after_ms is not an integer")
store_sequence = freshness.get("store_sequence")
if isinstance(store_sequence, bool) or not isinstance(store_sequence, int):
    raise SystemExit("frontend data_freshness.store_sequence is not an integer")
latest_event_ts_ms = freshness.get("latest_event_ts_ms")
latest_event_age_ms = freshness.get("latest_event_age_ms")
if latest_event_ts_ms is not None and (isinstance(latest_event_ts_ms, bool) or not isinstance(latest_event_ts_ms, int)):
    raise SystemExit("frontend data_freshness.latest_event_ts_ms is not an integer or null")
if latest_event_age_ms is not None and (isinstance(latest_event_age_ms, bool) or not isinstance(latest_event_age_ms, int)):
    raise SystemExit("frontend data_freshness.latest_event_age_ms is not an integer or null")
print(body.get("started_at", ""))
print(total_loaded)
print(refresh_errors)
print(release.get("sha") or "")
print(release.get("image") or "")
print(release.get("profile") or "")
print(freshness.get("latest_event_ts_ms") if freshness.get("latest_event_ts_ms") is not None else "")
print(freshness.get("latest_event_age_ms") if freshness.get("latest_event_age_ms") is not None else "")
print(freshness.get("symbol") or "")
print(freshness.get("source_event_id") or "")
print(readiness_status)
print(str(readiness.get("stale")).lower())
print(str(readiness.get("degraded")).lower())
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
    db_probe cursorCommitSeq --cursor-name="$FEATUREPLANT_DB_CURSOR_NAME"
}

pipeline_reliability_snapshot() {
    db_probe pipelineReliabilitySnapshot \
        --cursor-name="$FEATUREPLANT_DB_CURSOR_NAME" \
        --window-seconds="$LIVE_PRODUCT_RELIABILITY_WINDOW_SECONDS" \
        --row-limit="$LIVE_PRODUCT_RELIABILITY_ROW_LIMIT"
}

check_pipeline_reliability_snapshot() {
    snapshot_file="$tmpdir/pipeline-reliability.txt"
    pipeline_reliability_snapshot > "$snapshot_file"
    python3 - "$snapshot_file" <<'PY'
import sys

path = sys.argv[1]
line = open(path, "r", encoding="utf-8").read().strip()
fields = line.split("|")
names = (
    "status",
    "window_seconds",
    "row_limit",
    "raw_recent_count",
    "raw_latest_receive_ts_ns",
    "raw_latest_age_ms",
    "canonical_recent_count",
    "canonical_max_commit_seq",
    "canonical_latest_age_ms",
    "cursor_commit_seq",
    "cursor_lag_events",
    "feature_recent_count",
    "feature_latest_event_ts_ms",
    "feature_latest_age_ms",
    "raw_without_canonical_count",
)
if len(fields) != len(names):
    raise SystemExit(f"pipelineReliabilitySnapshot returned {len(fields)} fields, expected {len(names)}: {line!r}")
values = dict(zip(names, fields))
if values["status"] not in ("ok", "stale", "degraded"):
    raise SystemExit(f"pipelineReliabilitySnapshot status is invalid: {values['status']!r}")
for name in names[1:]:
    try:
        parsed = int(values[name])
    except ValueError as exc:
        raise SystemExit(f"pipelineReliabilitySnapshot {name} is not an integer: {values[name]!r}") from exc
    if name.endswith("_age_ms"):
        if parsed < -1:
            raise SystemExit(f"pipelineReliabilitySnapshot {name} is below -1: {parsed}")
    elif parsed < 0:
        raise SystemExit(f"pipelineReliabilitySnapshot {name} is negative: {parsed}")

print(
    "PASS pipeline_reliability "
    f"status={values['status']} "
    f"window_seconds={values['window_seconds']} "
    f"row_limit={values['row_limit']} "
    f"raw_recent={values['raw_recent_count']} "
    f"raw_latest_receive_ts_ns={values['raw_latest_receive_ts_ns']} "
    f"canonical_recent={values['canonical_recent_count']} "
    f"canonical_max_commit_seq={values['canonical_max_commit_seq']} "
    f"cursor_commit_seq={values['cursor_commit_seq']} "
    f"cursor_lag_events={values['cursor_lag_events']} "
    f"feature_recent={values['feature_recent_count']} "
    f"raw_without_canonical={values['raw_without_canonical_count']}"
)
PY
}

latest_non_smoke_canonical_after() {
    db_probe latestNonSmokeCanonicalAfter --after-commit-seq="$1"
}

feature_outputs_for_source_event() {
    db_probe featureOutputsForSourceEvent --source-event-id="$1"
}

latest_non_smoke_feature_output_after() {
    db_probe latestNonSmokeFeatureOutputAfter --after-commit-seq="$1"
}

seed_canonical_events() {
    db_probe seedCanonicalEvents --run-id="$1" --market-ticker="$2" --prefix="$3"
}

wait_featureplant_followed_seed() {
    expected_commit_seq="$2"
    attempt=1
    while :; do
        progress="$(db_probe featureOutputsForPrefix --prefix="$1" --cursor-name="$FEATUREPLANT_DB_CURSOR_NAME")"
        output_count="$(printf '%s\n' "$progress" | awk -F '|' 'NR == 1 {print $1}')"
        current_cursor_seq="$(printf '%s\n' "$progress" | awk -F '|' 'NR == 1 {print $2}')"
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
        readiness_status="$(printf '%s\n' "$health" | sed -n '11p')"
        readiness_stale="$(printf '%s\n' "$health" | sed -n '12p')"
        readiness_degraded="$(printf '%s\n' "$health" | sed -n '13p')"
        if [ "$started_at" = "$expected_started_at" ] \
            && [ "$total_loaded" -gt "$min_total_loaded" ] \
            && [ "$refresh_errors" -le "$max_refresh_errors" ]; then
            printf '%s\n%s\n%s\n%s\n%s\n%s\n' \
                "$started_at" "$total_loaded" "$refresh_errors" \
                "$readiness_status" "$readiness_stale" "$readiness_degraded"
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
    chart_file="$tmpdir/frontend-lightweight-charts.js"
    curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FRONTEND_BASE_URL}/" -o "$index_file"
    curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FRONTEND_BASE_URL}/app.js" -o "$app_file"
    curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FRONTEND_BASE_URL}/styles.css" -o "$css_file"
    curl -fsS --noproxy "$FRONTEND_NO_PROXY" \
        "${FRONTEND_BASE_URL}/vendor/lightweight-charts-4.2.0.standalone.production.js" -o "$chart_file"
    grep -q 'Kalshi Product Dashboard' "$index_file"
    grep -q '<link rel="stylesheet" href="styles.css" />' "$index_file"
    grep -q '<script src="vendor/lightweight-charts-4.2.0.standalone.production.js"></script>' "$index_file"
    grep -q '<script src="app.js"></script>' "$index_file"
    grep -q 'market-list' "$index_file"
    grep -q 'feature-list' "$index_file"
    grep -q 'Runtime Health' "$index_file"
    grep -q 'release-identity' "$index_file"
    grep -q 'health-data-age' "$index_file"
    grep -q 'quote-update-health' "$index_file"
    grep -q 'same origin' "$index_file"
    grep -q '/quotes/stream' "$app_file"
    grep -q '/quotes/updates' "$app_file"
    grep -q 'EventSource' "$app_file"
    grep -q '/markets?limit=100' "$app_file"
    grep -q '/features?symbol=' "$app_file"
    grep -q '/health' "$app_file"
    grep -q 'body.release' "$app_file"
    grep -q 'body.data_freshness' "$app_file"
    grep -q 'body.quote_streams' "$app_file"
    grep -q 'body.quote_updates' "$app_file"
    grep -q 'latest_event_ts_ms' "$app_file"
    grep -q 'chart-container' "$css_file"
    grep -q 'LightweightCharts' "$chart_file"
    if grep -Eiq '(https?://|//)[^"'"'"' ]*(unpkg|jsdelivr|cdnjs|cdn)' \
        "$index_file" "$app_file" "$css_file" "$chart_file"; then
        printf 'frontend static UI must not reference external CDN assets\n' >&2
        exit 1
    fi
    printf 'PASS frontend_static_ui url=%s/\n' "$FRONTEND_BASE_URL"
}

check_product_browser_ui() {
    if ! is_true "$LIVE_PRODUCT_BROWSER_SMOKE_ENABLED"; then
        return 0
    fi
    FRONTEND_BASE_URL="$FRONTEND_BASE_URL" \
        FRONTEND_NO_PROXY="$FRONTEND_NO_PROXY" \
        sh "$FRONTEND_BROWSER_SMOKE_SCRIPT"
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

wait_frontend_quote_stream() {
    market="$1"
    expected_midpoint_micros="$2"
    encoded_market="$(urlencode "$market")"
    output="$tmpdir/frontend.quotes.sse"
    attempt=1
    while :; do
        if fetch_sse_stream "/quotes/stream?symbols=${encoded_market}" "$output" \
            && python3 - "$output" "$market" "$expected_midpoint_micros" <<'PY'
import json
import sys

path, market = sys.argv[1:3]
expected_midpoint = int(sys.argv[3])
with open(path, "r", encoding="utf-8") as handle:
    lines = handle.read().splitlines()

data_lines = []
events = []
for line in lines:
    if not line:
        if data_lines:
            events.append("\n".join(data_lines))
            data_lines = []
        continue
    if line.startswith("data:"):
        value = line[len("data:"):]
        if value.startswith(" "):
            value = value[1:]
        data_lines.append(value)
if data_lines:
    events.append("\n".join(data_lines))
if not events:
    raise SystemExit("quote stream missing SSE data event")
body = json.loads(events[0])
for key in ("sequence", "server_ts_ms"):
    value = body.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise SystemExit(f"quote stream {key} is not an integer")
if not isinstance(body.get("changed"), bool):
    raise SystemExit("quote stream changed is not a boolean")
quotes = body.get("quotes")
if not isinstance(quotes, list):
    raise SystemExit("quote stream quotes missing")
for quote in quotes:
    if isinstance(quote, dict) and quote.get("symbol") == market:
        if quote.get("midpoint_micros") == expected_midpoint:
            raise SystemExit(0)
        raise SystemExit("quote stream midpoint mismatch")
raise SystemExit("quote stream market quote not visible")
PY
        then
            printf 'PASS frontend_quotes_stream market=%s midpoint_micros=%s\n' "$market" "$expected_midpoint_micros"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'frontend quote stream did not expose seeded BBO: market=%s midpoint_micros=%s\n' \
                "$market" "$expected_midpoint_micros" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

wait_featureplant_cursor_caught_up() {
    expected_commit_seq="$1"
    attempt=1
    while :; do
        current_cursor_seq="$(cursor_commit_seq)"
        if [ "$current_cursor_seq" -ge "$expected_commit_seq" ]; then
            printf '%s\n' "$current_cursor_seq"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_DB_ATTEMPTS" ]; then
            printf 'FeaturePlant cursor did not catch up to live canonical event: cursor=%s expected_cursor_at_least=%s\n' \
                "$current_cursor_seq" "$expected_commit_seq" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_DB_RETRY_SLEEP_SECONDS"
    done
}

wait_live_feature_output_for_source_event() {
    source_event_id="$1"
    attempt=1
    while :; do
        output="$(feature_outputs_for_source_event "$source_event_id")"
        output_count="$(printf '%s\n' "$output" | awk -F '|' 'NR == 1 {print $1}')"
        if [ -n "$output_count" ] && [ "$output_count" -ge 1 ]; then
            printf '%s\n' "$output"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_DB_ATTEMPTS" ]; then
            printf 'FeaturePlant did not write non-smoke feature_output for source_event_id=%s\n' \
                "$source_event_id" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_DB_RETRY_SLEEP_SECONDS"
    done
}

wait_latest_live_feature_output_after() {
    baseline_commit_seq="$1"
    attempt=1
    while :; do
        output="$(latest_non_smoke_feature_output_after "$baseline_commit_seq")"
        if [ -n "$output" ]; then
            printf '%s\n' "$output"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_DB_ATTEMPTS" ]; then
            printf 'No non-smoke feature_output joined to canonical_events after baseline_commit_seq=%s\n' \
                "$baseline_commit_seq" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_DB_RETRY_SLEEP_SECONDS"
    done
}

wait_frontend_live_feature_output() {
    market="$1"
    feature="$2"
    source_event_id="$3"
    encoded_market="$(urlencode "$market")"
    encoded_feature="$(urlencode "$feature")"
    output="$tmpdir/frontend.live.features.json"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" \
            "${FRONTEND_BASE_URL}/features?symbol=${encoded_market}&feature=${encoded_feature}&limit=20" \
            -o "$output" \
            && python3 - "$output" "$market" "$feature" "$source_event_id" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
market = sys.argv[2]
feature = sys.argv[3]
source_event_id = sys.argv[4]
if body.get("symbol") != market or body.get("feature") != feature:
    raise SystemExit("feature response metadata mismatch")
outputs = body.get("outputs")
if not isinstance(outputs, list):
    raise SystemExit("feature outputs missing")
for output in outputs:
    if isinstance(output, dict) and output.get("source_event_id") == source_event_id:
        if not isinstance(output.get("values"), dict):
            raise SystemExit("live feature has no values")
        raise SystemExit(0)
raise SystemExit("live feature output not visible")
PY
        then
            printf 'PASS frontend_live_feature_output market=%s feature=%s source_event_id=%s\n' \
                "$market" "$feature" "$source_event_id"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'frontend did not expose live feature output: market=%s feature=%s source_event_id=%s\n' \
                "$market" "$feature" "$source_event_id" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

wait_frontend_live_quote() {
    market="$1"
    min_event_ts_ms="$2"
    encoded_market="$(urlencode "$market")"
    output="$tmpdir/frontend.live.quotes.json"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" \
            "${FRONTEND_BASE_URL}/quotes?symbols=${encoded_market}" \
            -o "$output" \
            && python3 - "$output" "$market" "$min_event_ts_ms" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
market = sys.argv[2]
min_event_ts_ms = int(sys.argv[3])
quotes = body.get("quotes")
if not isinstance(quotes, list):
    raise SystemExit("quotes missing")
for quote in quotes:
    if isinstance(quote, dict) and quote.get("symbol") == market:
        source_event_id = quote.get("source_event_id") or ""
        event_ts_ms = quote.get("event_ts_ms")
        if source_event_id.startswith("live-product-smoke-"):
            raise SystemExit("quote still points at smoke source")
        if not source_event_id:
            raise SystemExit("quote source_event_id missing")
        if isinstance(event_ts_ms, bool) or not isinstance(event_ts_ms, int) or event_ts_ms < min_event_ts_ms:
            raise SystemExit("quote event_ts_ms is older than live proof event")
        raise SystemExit(0)
raise SystemExit("live quote not visible")
PY
        then
            printf 'PASS frontend_live_quote market=%s min_event_ts_ms=%s\n' "$market" "$min_event_ts_ms"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'frontend quote did not expose non-smoke BBO: market=%s min_event_ts_ms=%s\n' \
                "$market" "$min_event_ts_ms" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
}

wait_frontend_health_non_smoke_freshness() {
    min_event_ts_ms="$1"
    output="$tmpdir/frontend.live.health.json"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" "$FRONTEND_HEALTH_URL" -o "$output" \
            && python3 - "$output" "$min_event_ts_ms" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
min_event_ts_ms = int(sys.argv[2])
freshness = body.get("data_freshness")
if not isinstance(freshness, dict):
    raise SystemExit("data_freshness missing")
source_event_id = freshness.get("source_event_id") or ""
event_ts_ms = freshness.get("latest_event_ts_ms")
if not source_event_id or source_event_id.startswith("live-product-smoke-"):
    raise SystemExit("freshness does not point at non-smoke source")
if isinstance(event_ts_ms, bool) or not isinstance(event_ts_ms, int) or event_ts_ms < min_event_ts_ms:
    raise SystemExit("freshness event_ts_ms is older than live proof event")
PY
        then
            printf 'PASS frontend_live_health_freshness min_event_ts_ms=%s\n' "$min_event_ts_ms"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            printf 'frontend health did not expose non-smoke data freshness at or after event_ts_ms=%s\n' \
                "$min_event_ts_ms" >&2
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
    baseline_commit_seq="$1"
    attempt=1
    while :; do
        live_event="$(latest_non_smoke_canonical_after "$baseline_commit_seq")"
        if [ -n "$live_event" ]; then
            live_event_id="$(printf '%s\n' "$live_event" | awk -F '|' 'NR == 1 {print $1}')"
            live_market="$(printf '%s\n' "$live_event" | awk -F '|' 'NR == 1 {print $2}')"
            live_stream="$(printf '%s\n' "$live_event" | awk -F '|' 'NR == 1 {print $3}')"
            live_commit_seq="$(printf '%s\n' "$live_event" | awk -F '|' 'NR == 1 {print $4}')"
            live_event_ts_ms="$(printf '%s\n' "$live_event" | awk -F '|' 'NR == 1 {print $5}')"
            if [ -z "$live_event_id" ] || [ -z "$live_market" ] || [ -z "$live_stream" ] \
                || [ -z "$live_commit_seq" ] || [ -z "$live_event_ts_ms" ]; then
                printf 'live data requirement failed: malformed live canonical row: %s\n' "$live_event" >&2
                return 1
            fi
            live_cursor_seq="$(wait_featureplant_cursor_caught_up "$live_commit_seq")"
            live_feature="$(wait_live_feature_output_for_source_event "$live_event_id")"
            live_feature_count="$(printf '%s\n' "$live_feature" | awk -F '|' 'NR == 1 {print $1}')"
            live_feature_name="$(printf '%s\n' "$live_feature" | awk -F '|' 'NR == 1 {print $2}')"
            live_feature_market="$(printf '%s\n' "$live_feature" | awk -F '|' 'NR == 1 {print $3}')"
            live_feature_event_ts_ms="$(printf '%s\n' "$live_feature" | awk -F '|' 'NR == 1 {print $4}')"
            latest_live_feature="$(wait_latest_live_feature_output_after "$baseline_commit_seq")"
            latest_live_feature_source="$(printf '%s\n' "$latest_live_feature" | awk -F '|' 'NR == 1 {print $1}')"
            latest_live_feature_name="$(printf '%s\n' "$latest_live_feature" | awk -F '|' 'NR == 1 {print $2}')"
            latest_live_feature_market="$(printf '%s\n' "$latest_live_feature" | awk -F '|' 'NR == 1 {print $3}')"
            latest_live_feature_commit_seq="$(printf '%s\n' "$latest_live_feature" | awk -F '|' 'NR == 1 {print $6}')"
            wait_frontend_live_feature_output "$live_feature_market" "$live_feature_name" "$live_event_id"
            wait_frontend_health_non_smoke_freshness "$live_feature_event_ts_ms"
            if [ "$live_feature_name" = "feature.bbo" ]; then
                wait_frontend_live_quote "$live_feature_market" "$live_feature_event_ts_ms"
            fi
            printf 'PASS live_data baseline_commit_seq=%s live_event_id=%s market=%s stream=%s commit_seq=%s event_ts_ms=%s cursor_after=%s feature_outputs_for_source=%s feature=%s latest_feature_source_event_id=%s latest_feature=%s latest_feature_market=%s latest_feature_commit_seq=%s\n' \
                "$baseline_commit_seq" "$live_event_id" "$live_market" "$live_stream" "$live_commit_seq" \
                "$live_event_ts_ms" "$live_cursor_seq" "$live_feature_count" "$live_feature_name" \
                "$latest_live_feature_source" "$latest_live_feature_name" "$latest_live_feature_market" \
                "$latest_live_feature_commit_seq"
            return 0
        fi
        if [ "$attempt" -ge "$SMOKE_DB_ATTEMPTS" ]; then
            printf 'live data requirement failed: no feature-eligible non-smoke canonical_events after baseline_commit_seq=%s\n' \
                "$baseline_commit_seq" >&2
            print_diagnostics
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_DB_RETRY_SLEEP_SECONDS"
    done
}

compose ps >/dev/null
validate_live_db_config

wait_plain_health wsclient "$WSCLIENT_HEALTH_URL"
wait_streamtap_health
wait_plain_health featureplant-db-follower "$FEATUREPLANT_HEALTH_URL"
frontend_before="$(wait_frontend_ready)"
frontend_started_at_before="$(printf '%s\n' "$frontend_before" | sed -n '1p')"
frontend_loaded_before="$(printf '%s\n' "$frontend_before" | sed -n '2p')"
frontend_errors_before="$(printf '%s\n' "$frontend_before" | sed -n '3p')"
frontend_release_sha="$(printf '%s\n' "$frontend_before" | sed -n '4p')"
frontend_release_image="$(printf '%s\n' "$frontend_before" | sed -n '5p')"
frontend_release_profile="$(printf '%s\n' "$frontend_before" | sed -n '6p')"
frontend_freshness_event_ts_ms="$(printf '%s\n' "$frontend_before" | sed -n '7p')"
frontend_freshness_age_ms="$(printf '%s\n' "$frontend_before" | sed -n '8p')"
frontend_freshness_symbol="$(printf '%s\n' "$frontend_before" | sed -n '9p')"
frontend_freshness_source_event_id="$(printf '%s\n' "$frontend_before" | sed -n '10p')"
frontend_readiness_status="$(printf '%s\n' "$frontend_before" | sed -n '11p')"
frontend_readiness_stale="$(printf '%s\n' "$frontend_before" | sed -n '12p')"
frontend_readiness_degraded="$(printf '%s\n' "$frontend_before" | sed -n '13p')"
printf 'PASS health service=frontend-adapter url=%s started_at=%s feature_output_refresh_total_loaded=%s refresh_errors=%s release_sha=%s release_image=%s release_profile=%s freshness_event_ts_ms=%s freshness_age_ms=%s freshness_symbol=%s freshness_source_event_id=%s product_readiness_status=%s product_readiness_stale=%s product_readiness_degraded=%s\n' \
    "$FRONTEND_HEALTH_URL" "$frontend_started_at_before" "$frontend_loaded_before" "$frontend_errors_before" "$frontend_release_sha" "$frontend_release_image" "$frontend_release_profile" "$frontend_freshness_event_ts_ms" "$frontend_freshness_age_ms" "$frontend_freshness_symbol" "$frontend_freshness_source_event_id" "$frontend_readiness_status" "$frontend_readiness_stale" "$frontend_readiness_degraded"
check_product_static_ui
check_product_browser_ui

cursor_before="$(cursor_commit_seq)"
max_commit_before="$(db_probe maxCanonicalCommitSeq)"
if [ "$cursor_before" -gt "$max_commit_before" ]; then
    printf 'FeaturePlant cursor is ahead of canonical_events: cursor=%s max_commit_seq=%s\n' \
        "$cursor_before" "$max_commit_before" >&2
    exit 1
fi
check_pipeline_reliability_snapshot
check_optional_live_data "$max_commit_before"

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
frontend_readiness_status_after="$(printf '%s\n' "$frontend_after" | sed -n '4p')"
frontend_readiness_stale_after="$(printf '%s\n' "$frontend_after" | sed -n '5p')"
frontend_readiness_degraded_after="$(printf '%s\n' "$frontend_after" | sed -n '6p')"
wait_frontend_feature_output "$market_ticker" "$bbo_event_id"
wait_frontend_quote "$market_ticker"
wait_frontend_quote_stream "$market_ticker" 461500

wait_plain_health wsclient "$WSCLIENT_HEALTH_URL"
wait_streamtap_health
wait_plain_health featureplant-db-follower "$FEATUREPLANT_HEALTH_URL"

printf 'PASS live_product_smoke market=%s run_id=%s cursor_before=%s target_commit_seq=%s cursor_after=%s feature_outputs=%s frontend_started_at=%s frontend_total_loaded_before=%s frontend_total_loaded_after=%s frontend_refresh_errors_after=%s product_readiness_status=%s product_readiness_stale=%s product_readiness_degraded=%s\n' \
    "$market_ticker" "$run_id" "$cursor_before" "$target_commit_seq" "$cursor_after" "$feature_outputs_after" \
    "$frontend_started_at_after" "$frontend_loaded_before" "$frontend_loaded_after" "$frontend_errors_after" \
    "$frontend_readiness_status_after" "$frontend_readiness_stale_after" "$frontend_readiness_degraded_after"
