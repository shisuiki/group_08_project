#!/bin/sh
set -eu

FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://127.0.0.1:${FRONTEND_ADAPTER_HOST_PORT:-8090}}"
FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
EXPECTED_FEATURE_SOURCE="${EXPECTED_FEATURE_SOURCE:-latest_market_state}"
EXPECTED_FEATURE_OUTPUT_REFRESH_ENABLED="${EXPECTED_FEATURE_OUTPUT_REFRESH_ENABLED:-true}"
EXPECTED_FEATURE_OUTPUT_REFRESH_RUNNING="${EXPECTED_FEATURE_OUTPUT_REFRESH_RUNNING:-$EXPECTED_FEATURE_OUTPUT_REFRESH_ENABLED}"
EXPECTED_FRONTEND_STARTED_AT="${EXPECTED_FRONTEND_STARTED_AT:-}"
EXPECTED_REFRESH_TOTAL_LOADED_MIN="${EXPECTED_REFRESH_TOTAL_LOADED_MIN:-}"
EXPECTED_MARKET_METADATA_STATUS="${EXPECTED_MARKET_METADATA_STATUS:-loaded}"
EXPECTED_MARKET_METADATA_MIN_ROWS="${EXPECTED_MARKET_METADATA_MIN_ROWS:-1}"
EXPECTED_KALSHI_RELEASE_SHA="${EXPECTED_KALSHI_RELEASE_SHA:-}"
EXPECTED_KALSHI_APP_IMAGE="${EXPECTED_KALSHI_APP_IMAGE:-}"
EXPECTED_KALSHI_DEPLOY_PROFILE="${EXPECTED_KALSHI_DEPLOY_PROFILE:-}"
EXPECTED_KALSHI_GITHUB_RUN_ID="${EXPECTED_KALSHI_GITHUB_RUN_ID:-}"
EXPECTED_KALSHI_GITHUB_RUN_ATTEMPT="${EXPECTED_KALSHI_GITHUB_RUN_ATTEMPT:-}"
DEMO_FEATURE="${DEMO_FEATURE:-feature.bbo}"
DEMO_LIMIT="${DEMO_LIMIT:-5}"
DEMO_HISTORY_RESOLUTION="${DEMO_HISTORY_RESOLUTION:-1}"
DEMO_METADATA_QUERY="${DEMO_METADATA_QUERY:-DEMO-DBPRIMARY}"
DEMO_METADATA_SEARCH_QUERY="${DEMO_METADATA_SEARCH_QUERY:-$DEMO_METADATA_QUERY}"
DEMO_MARKET_EVENT_TICKER="${DEMO_MARKET_EVENT_TICKER:-DEMO-DBPRIMARY-26MAY19}"
DEMO_MARKET_SERIES_TICKER="${DEMO_MARKET_SERIES_TICKER:-DEMO-DBPRIMARY}"
DEMO_MARKET_STATUS="${DEMO_MARKET_STATUS:-open}"
DEMO_MARKET_LIMIT="${DEMO_MARKET_LIMIT:-5}"
SMOKE_HTTP_ATTEMPTS="${SMOKE_HTTP_ATTEMPTS:-20}"
SMOKE_HTTP_RETRY_SLEEP_SECONDS="${SMOKE_HTTP_RETRY_SLEEP_SECONDS:-1}"

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/db-primary-demo-smoke.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT INT HUP TERM

urlencode() {
    python3 - "$1" <<'PY'
import sys
from urllib.parse import quote
print(quote(sys.argv[1], safe=""))
PY
}

fetch_json() {
    endpoint="$1"
    output="$2"
    attempt=1
    while :; do
        if curl -fsS --noproxy "$FRONTEND_NO_PROXY" "${FRONTEND_BASE_URL}${endpoint}" -o "$output"; then
            break
        fi
        if [ "$attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
            return 1
        fi
        attempt=$((attempt + 1))
        sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
    done
    python3 - "$output" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    json.load(handle)
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
    grep -q 'market-search' "$index_file"
    grep -q 'market-status-filter' "$index_file"
    grep -q 'market-state' "$index_file"
    grep -q 'feature-list' "$index_file"
    grep -q 'Runtime Health' "$index_file"
    grep -q 'release-identity' "$index_file"
    grep -q 'health-data-age' "$index_file"
    grep -q 'quote-update-health' "$index_file"
    grep -q 'same origin' "$index_file"
    grep -q '/quotes/stream' "$app_file"
    grep -q '/quotes/updates' "$app_file"
    grep -q 'EventSource' "$app_file"
    grep -q 'MARKET_CATALOG_LIMIT' "$app_file"
    grep -q "'/markets?' + params.join('&')" "$app_file"
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

health_json="$tmpdir/health.json"
health_selection="$tmpdir/health-selection.txt"
health_attempt=1
while :; do
    if fetch_json "/health" "$health_json" && python3 - "$health_json" \
        "$EXPECTED_FEATURE_SOURCE" \
        "$EXPECTED_FEATURE_OUTPUT_REFRESH_ENABLED" \
        "$EXPECTED_FEATURE_OUTPUT_REFRESH_RUNNING" \
        "$EXPECTED_FRONTEND_STARTED_AT" \
        "$EXPECTED_REFRESH_TOTAL_LOADED_MIN" \
        "$EXPECTED_MARKET_METADATA_STATUS" \
        "$EXPECTED_MARKET_METADATA_MIN_ROWS" \
        "$EXPECTED_KALSHI_RELEASE_SHA" \
        "$EXPECTED_KALSHI_APP_IMAGE" \
        "$EXPECTED_KALSHI_DEPLOY_PROFILE" \
        "$EXPECTED_KALSHI_GITHUB_RUN_ID" \
        "$EXPECTED_KALSHI_GITHUB_RUN_ATTEMPT" > "$health_selection" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
if body.get("service") != "frontend-adapter":
    raise SystemExit("health check failed: service is not frontend-adapter")
feature_source = body.get("feature_source")
if not feature_source:
    raise SystemExit("health check failed: feature_source is missing")
expected = sys.argv[2].strip() if len(sys.argv) > 2 else ""
expected = expected.replace("-", "_")
if expected == "latest_state":
    expected = "latest_market_state"
if expected and feature_source != expected:
    raise SystemExit(
        f"health check failed: feature_source is {feature_source!r}, expected {expected!r}; "
        "restart frontend-adapter with FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs or latest_market_state"
    )
refresh = body.get("feature_output_refresh")
if feature_source in ("feature_outputs", "latest_market_state"):
    if not isinstance(refresh, dict):
        raise SystemExit("health check failed: feature_output_refresh is missing")
    expected_refresh = sys.argv[3].strip().lower() if len(sys.argv) > 3 else ""
    if expected_refresh:
        expected_enabled = expected_refresh == "true"
        actual_enabled = refresh.get("enabled")
        if actual_enabled != expected_enabled:
            raise SystemExit(
                f"health check failed: feature_output_refresh.enabled is {actual_enabled!r}, "
                f"expected {expected_enabled!r}"
            )
    expected_running = sys.argv[4].strip().lower() if len(sys.argv) > 4 else ""
    if expected_running:
        expected_running_bool = expected_running == "true"
        actual_running = refresh.get("running")
        if actual_running != expected_running_bool:
            raise SystemExit(
                f"health check failed: feature_output_refresh.running is {actual_running!r}, "
                f"expected {expected_running_bool!r}"
            )
    raw_total_loaded = refresh.get("total_loaded")
    if isinstance(raw_total_loaded, bool) or not isinstance(raw_total_loaded, int):
        raise SystemExit("health check failed: feature_output_refresh.total_loaded is not an integer")
    min_total_loaded_raw = sys.argv[6].strip() if len(sys.argv) > 6 else ""
    if min_total_loaded_raw:
        min_total_loaded = int(min_total_loaded_raw)
        if raw_total_loaded < min_total_loaded:
            raise SystemExit(
                f"health check failed: feature_output_refresh.total_loaded is {raw_total_loaded}, "
                f"expected at least {min_total_loaded}"
            )
started_at = body.get("started_at")
expected_started_at = sys.argv[5].strip() if len(sys.argv) > 5 else ""
if expected_started_at and started_at != expected_started_at:
    raise SystemExit(
        f"health check failed: started_at is {started_at!r}, expected unchanged {expected_started_at!r}"
    )
metadata = body.get("market_metadata")
if not isinstance(metadata, dict):
    raise SystemExit("health check failed: market_metadata is missing")
metadata_status = metadata.get("status")
if not metadata_status:
    raise SystemExit("health check failed: market_metadata.status is missing")
expected_metadata_status = sys.argv[7].strip() if len(sys.argv) > 7 else ""
if expected_metadata_status and metadata_status != expected_metadata_status:
    raise SystemExit(
        f"health check failed: market_metadata.status is {metadata_status!r}, "
        f"expected {expected_metadata_status!r}; restart frontend-adapter after seeding metadata"
    )
raw_rows = metadata.get("markets")
if isinstance(raw_rows, bool) or not isinstance(raw_rows, int):
    raise SystemExit("health check failed: market_metadata.markets is not an integer")
try:
    min_rows = int(sys.argv[8]) if len(sys.argv) > 8 and sys.argv[8].strip() else 0
except ValueError as exc:
    raise SystemExit("health check failed: EXPECTED_MARKET_METADATA_MIN_ROWS must be an integer") from exc
if raw_rows < min_rows:
    raise SystemExit(
        f"health check failed: market_metadata.markets is {raw_rows}, expected at least {min_rows}"
    )
release = body.get("release")
if not isinstance(release, dict):
    raise SystemExit("health check failed: release is missing")
for field, expected_index in (
    ("sha", 9),
    ("image", 10),
    ("profile", 11),
    ("run_id", 12),
    ("run_attempt", 13),
):
    if field not in release:
        raise SystemExit(f"health check failed: release.{field} is missing")
    expected_value = sys.argv[expected_index].strip() if len(sys.argv) > expected_index else ""
    actual_value = release.get(field)
    if expected_value and actual_value != expected_value:
        raise SystemExit(
            f"health check failed: release.{field} is {actual_value!r}, expected {expected_value!r}"
        )
freshness = body.get("data_freshness")
if not isinstance(freshness, dict):
    raise SystemExit("health check failed: data_freshness is missing")
for key in ("latest_event_ts_ms", "latest_event_age_ms", "symbol", "feature_name", "source_event_id", "store_sequence"):
    if key not in freshness:
        raise SystemExit(f"health check failed: data_freshness.{key} is missing")
store = body.get("store")
store_sequence = freshness.get("store_sequence")
if isinstance(store_sequence, bool) or not isinstance(store_sequence, int):
    raise SystemExit("health check failed: data_freshness.store_sequence is not an integer")
if isinstance(store, dict) and isinstance(store.get("sequence"), int) and store.get("sequence") > 0:
    latest_event_ts_ms = freshness.get("latest_event_ts_ms")
    latest_event_age_ms = freshness.get("latest_event_age_ms")
    if isinstance(latest_event_ts_ms, bool) or not isinstance(latest_event_ts_ms, int):
        raise SystemExit("health check failed: data_freshness.latest_event_ts_ms is not an integer")
    if isinstance(latest_event_age_ms, bool) or not isinstance(latest_event_age_ms, int):
        raise SystemExit("health check failed: data_freshness.latest_event_age_ms is not an integer")
    if not freshness.get("symbol") or not freshness.get("feature_name"):
        raise SystemExit("health check failed: data_freshness latest event identity is incomplete")
print(feature_source)
print(metadata_status)
print(raw_rows)
print(refresh.get("enabled") if isinstance(refresh, dict) else "")
print(refresh.get("running") if isinstance(refresh, dict) else "")
print(refresh.get("total_loaded") if isinstance(refresh, dict) else "")
print(started_at)
print(release.get("sha") or "")
print(release.get("image") or "")
print(release.get("profile") or "")
print(freshness.get("latest_event_ts_ms") if freshness.get("latest_event_ts_ms") is not None else "")
print(freshness.get("latest_event_age_ms") if freshness.get("latest_event_age_ms") is not None else "")
print(freshness.get("symbol") or "")
print(freshness.get("source_event_id") or "")
PY
    then
        break
    fi
    if [ "$health_attempt" -ge "$SMOKE_HTTP_ATTEMPTS" ]; then
        printf 'health check failed after %s attempts\n' "$health_attempt" >&2
        exit 1
    fi
    health_attempt=$((health_attempt + 1))
    sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
done
feature_source="$(sed -n '1p' "$health_selection")"
market_metadata_status="$(sed -n '2p' "$health_selection")"
market_metadata_rows="$(sed -n '3p' "$health_selection")"
feature_output_refresh_enabled="$(sed -n '4p' "$health_selection")"
feature_output_refresh_running="$(sed -n '5p' "$health_selection")"
feature_output_refresh_total_loaded="$(sed -n '6p' "$health_selection")"
frontend_started_at="$(sed -n '7p' "$health_selection")"
release_sha="$(sed -n '8p' "$health_selection")"
release_image="$(sed -n '9p' "$health_selection")"
release_profile="$(sed -n '10p' "$health_selection")"
freshness_event_ts_ms="$(sed -n '11p' "$health_selection")"
freshness_age_ms="$(sed -n '12p' "$health_selection")"
freshness_symbol="$(sed -n '13p' "$health_selection")"
freshness_source_event_id="$(sed -n '14p' "$health_selection")"
printf 'PASS health service=frontend-adapter feature_source=%s expected_feature_source=%s feature_output_refresh_enabled=%s feature_output_refresh_running=%s feature_output_refresh_total_loaded=%s started_at=%s market_metadata_status=%s market_metadata_rows=%s release_sha=%s release_image=%s release_profile=%s freshness_event_ts_ms=%s freshness_age_ms=%s freshness_symbol=%s freshness_source_event_id=%s\n' \
    "$feature_source" "$EXPECTED_FEATURE_SOURCE" "$feature_output_refresh_enabled" "$feature_output_refresh_running" "$feature_output_refresh_total_loaded" "$frontend_started_at" "$market_metadata_status" "$market_metadata_rows" "$release_sha" "$release_image" "$release_profile" "$freshness_event_ts_ms" "$freshness_age_ms" "$freshness_symbol" "$freshness_source_event_id"

check_product_static_ui

symbols_json="$tmpdir/symbols.json"
fetch_json "/symbols" "$symbols_json"
symbol_selection="$tmpdir/symbol-selection.txt"
python3 - "$symbols_json" "${DEMO_SYMBOL:-}" "$DEMO_METADATA_QUERY" > "$symbol_selection" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
symbols = body.get("symbols")
if not isinstance(symbols, list) or not symbols:
    raise SystemExit("symbols check failed: /symbols returned no symbols")
override = sys.argv[2].strip() if len(sys.argv) > 2 else ""
preferred_query = sys.argv[3].strip() if len(sys.argv) > 3 else ""
selected = override
latest = ""
if not selected:
    for entry in symbols:
        candidate = entry.get("symbol") if isinstance(entry, dict) else None
        if candidate and preferred_query and preferred_query in candidate:
            selected = candidate
            break
if not selected:
    first = symbols[0]
    selected = first.get("symbol") if isinstance(first, dict) else None
    if not selected:
        raise SystemExit("symbols check failed: first symbol is missing")
for entry in symbols:
    if isinstance(entry, dict) and entry.get("symbol") == selected:
        raw_latest = entry.get("latest_event_ts_ms")
        if raw_latest is not None:
            latest = str(raw_latest)
        break
print(selected)
print(latest)
PY
selected_symbol="$(sed -n '1p' "$symbol_selection")"
latest_event_ts_ms="$(sed -n '2p' "$symbol_selection")"
printf 'PASS symbols selected=%s\n' "$selected_symbol"

encoded_symbol="$(urlencode "$selected_symbol")"
encoded_feature="$(urlencode "$DEMO_FEATURE")"
encoded_limit="$(urlencode "$DEMO_LIMIT")"
encoded_metadata_query="$(urlencode "$DEMO_METADATA_QUERY")"
encoded_metadata_search_query="$(urlencode "$DEMO_METADATA_SEARCH_QUERY")"
encoded_market_limit="$(urlencode "$DEMO_MARKET_LIMIT")"

if [ "$market_metadata_status" = "loaded" ]; then
    datafeed_symbol_json="$tmpdir/datafeed-symbol.json"
    fetch_json "/datafeed/symbols?symbol=${encoded_symbol}" "$datafeed_symbol_json"
    datafeed_symbol_metadata="$tmpdir/datafeed-symbol-metadata.txt"
    python3 - "$datafeed_symbol_json" "$selected_symbol" "$DEMO_MARKET_EVENT_TICKER" "$DEMO_MARKET_SERIES_TICKER" "$DEMO_MARKET_STATUS" > "$datafeed_symbol_metadata" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
expected_symbol = sys.argv[2]
expected_event = sys.argv[3]
expected_series = sys.argv[4]
expected_status = sys.argv[5]
if body.get("ticker") != expected_symbol or body.get("name") != expected_symbol:
    raise SystemExit("datafeed symbols metadata check failed: selected symbol mismatch")
for key, expected in (
    ("event_ticker", expected_event),
    ("series_ticker", expected_series),
    ("status", expected_status),
):
    if body.get(key) != expected:
        raise SystemExit(
            f"datafeed symbols metadata check failed: {key} is {body.get(key)!r}, expected {expected!r}"
        )
if "market_payload" in body or "rules_payload" in body:
    raise SystemExit("datafeed symbols metadata check failed: raw metadata payload leaked")
print(body.get("event_ticker"))
print(body.get("series_ticker"))
print(body.get("status"))
PY
    symbol_event_ticker="$(sed -n '1p' "$datafeed_symbol_metadata")"
    symbol_series_ticker="$(sed -n '2p' "$datafeed_symbol_metadata")"
    symbol_market_status="$(sed -n '3p' "$datafeed_symbol_metadata")"
    printf 'PASS datafeed_symbols symbol=%s event=%s series=%s status=%s\n' \
        "$selected_symbol" "$symbol_event_ticker" "$symbol_series_ticker" "$symbol_market_status"

    search_json="$tmpdir/datafeed-search.json"
    fetch_json "/datafeed/search?query=${encoded_metadata_search_query}&limit=${encoded_market_limit}" "$search_json"
    search_count="$(python3 - "$search_json" "$selected_symbol" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
expected_symbol = sys.argv[2]
if not isinstance(body, list) or not body:
    raise SystemExit("datafeed search metadata check failed: no results")
found = any(
    isinstance(entry, dict)
    and (entry.get("symbol") == expected_symbol or entry.get("ticker") == expected_symbol)
    for entry in body
)
if not found:
    raise SystemExit("datafeed search metadata check failed: selected metadata-backed market missing")
print(len(body))
PY
)"
    printf 'PASS datafeed_search query=%s count=%s\n' "$DEMO_METADATA_SEARCH_QUERY" "$search_count"

    markets_json="$tmpdir/markets.json"
    fetch_json "/markets?query=${encoded_metadata_query}&limit=${encoded_market_limit}" "$markets_json"
    market_count="$(python3 - "$markets_json" "$selected_symbol" "$DEMO_MARKET_EVENT_TICKER" "$DEMO_MARKET_SERIES_TICKER" "$DEMO_MARKET_STATUS" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
expected_symbol = sys.argv[2]
expected_event = sys.argv[3]
expected_series = sys.argv[4]
expected_status = sys.argv[5]
count = body.get("count")
markets = body.get("markets")
if not isinstance(count, int) or count <= 0:
    raise SystemExit("markets metadata check failed: count is not positive")
if not isinstance(markets, list) or not markets:
    raise SystemExit("markets metadata check failed: markets are empty")
selected = None
for row in markets:
    if isinstance(row, dict) and row.get("market_ticker") == expected_symbol:
        selected = row
        break
if selected is None:
    raise SystemExit("markets metadata check failed: selected market missing")
for key, expected in (
    ("event_ticker", expected_event),
    ("series_ticker", expected_series),
    ("status", expected_status),
):
    if selected.get(key) != expected:
        raise SystemExit(
            f"markets metadata check failed: {key} is {selected.get(key)!r}, expected {expected!r}"
        )
for forbidden in ("market_payload", "rules_payload", "payload"):
    if forbidden in selected:
        raise SystemExit(f"markets metadata check failed: compact response leaked {forbidden}")
print(count)
PY
)"
    printf 'PASS markets query=%s count=%s\n' "$DEMO_METADATA_QUERY" "$market_count"
else
    printf 'PASS market_metadata_checks_skipped status=%s\n' "$market_metadata_status"
fi

features_json="$tmpdir/features.json"
fetch_json "/features?symbol=${encoded_symbol}&feature=${encoded_feature}&limit=${encoded_limit}" "$features_json"
feature_selection="$tmpdir/feature-selection.txt"
python3 - "$features_json" "$selected_symbol" "$DEMO_FEATURE" > "$feature_selection" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
expected_symbol = sys.argv[2]
expected_feature = sys.argv[3]
if body.get("symbol") != expected_symbol:
    raise SystemExit("features check failed: symbol mismatch")
if body.get("feature") != expected_feature:
    raise SystemExit("features check failed: feature mismatch")
count = body.get("count")
outputs = body.get("outputs")
if not isinstance(count, int) or count <= 0:
    raise SystemExit("features check failed: count is not positive")
if not isinstance(outputs, list) or not outputs:
    raise SystemExit("features check failed: outputs are empty")
for index, output in enumerate(outputs):
    if not isinstance(output, dict):
        raise SystemExit(f"features check failed: output {index} is not an object")
    if output.get("feature_name") != expected_feature:
        raise SystemExit(f"features check failed: output {index} feature_name mismatch")
    if output.get("market_ticker") != expected_symbol:
        raise SystemExit(f"features check failed: output {index} market_ticker mismatch")
    if not output.get("source_event_id"):
        raise SystemExit(f"features check failed: output {index} source_event_id is missing")
    if not isinstance(output.get("event_ts_ms"), int):
        raise SystemExit(f"features check failed: output {index} event_ts_ms is not an integer")
    if "values" not in output or output["values"] is None or not isinstance(output["values"], dict):
        raise SystemExit(f"features check failed: output {index} has no values")
    values = output["values"]
    for key in ("bid_price_micros", "ask_price_micros", "midpoint_micros"):
        value = values.get(key)
        if isinstance(value, bool) or not isinstance(value, int):
            raise SystemExit(f"features check failed: output {index} {key} is not an integer")
latest = outputs[-1]
values = latest["values"]
print(count)
print(latest["source_event_id"])
print(latest["event_ts_ms"])
print(values["midpoint_micros"])
print(values["bid_price_micros"])
print(values["ask_price_micros"])
PY
feature_count="$(sed -n '1p' "$feature_selection")"
feature_source_event_id="$(sed -n '2p' "$feature_selection")"
feature_event_ts_ms="$(sed -n '3p' "$feature_selection")"
feature_midpoint_micros="$(sed -n '4p' "$feature_selection")"
feature_bid_micros="$(sed -n '5p' "$feature_selection")"
feature_ask_micros="$(sed -n '6p' "$feature_selection")"
printf 'PASS features symbol=%s feature=%s count=%s latest_source_event_id=%s event_ts_ms=%s midpoint_micros=%s\n' \
    "$selected_symbol" "$DEMO_FEATURE" "$feature_count" "$feature_source_event_id" "$feature_event_ts_ms" "$feature_midpoint_micros"

quotes_json="$tmpdir/quotes.json"
fetch_json "/quotes?symbols=${encoded_symbol}" "$quotes_json"
python3 - "$quotes_json" "$selected_symbol" "$feature_source_event_id" "$feature_event_ts_ms" "$feature_midpoint_micros" "$feature_bid_micros" "$feature_ask_micros" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
quotes = body.get("quotes")
if not isinstance(quotes, list) or not quotes:
    raise SystemExit("quotes check failed: quotes are empty")
quote = quotes[0]
if quote.get("symbol") != sys.argv[2]:
    raise SystemExit("quotes check failed: selected symbol is missing")
expected_source = sys.argv[3]
expected_event_ts = int(sys.argv[4])
expected_midpoint = int(sys.argv[5])
expected_bid = int(sys.argv[6])
expected_ask = int(sys.argv[7])
if quote.get("source_event_id") != expected_source:
    raise SystemExit(
        f"quotes check failed: source_event_id is {quote.get('source_event_id')!r}, expected {expected_source!r}"
    )
for key, expected in (
    ("event_ts_ms", expected_event_ts),
    ("midpoint_micros", expected_midpoint),
    ("bid_micros", expected_bid),
    ("ask_micros", expected_ask),
):
    value = quote.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value != expected:
        raise SystemExit(f"quotes check failed: {key} is {value!r}, expected {expected!r}")
PY
printf 'PASS quotes symbol=%s midpoint_micros=%s source_event_id=%s event_ts_ms=%s\n' \
    "$selected_symbol" "$feature_midpoint_micros" "$feature_source_event_id" "$feature_event_ts_ms"

quotes_sse="$tmpdir/quotes.sse"
fetch_sse_stream "/quotes/stream?symbols=${encoded_symbol}" "$quotes_sse"
python3 - "$quotes_sse" "$selected_symbol" "$feature_source_event_id" "$feature_event_ts_ms" "$feature_midpoint_micros" "$feature_bid_micros" "$feature_ask_micros" <<'PY'
import json
import sys

path, expected_symbol, expected_source = sys.argv[1:4]
expected_event_ts = int(sys.argv[4])
expected_midpoint = int(sys.argv[5])
expected_bid = int(sys.argv[6])
expected_ask = int(sys.argv[7])
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
    raise SystemExit("quote stream check failed: no SSE data event")
body = json.loads(events[0])
for key in ("sequence", "server_ts_ms"):
    value = body.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise SystemExit(f"quote stream check failed: {key} is not an integer")
if not isinstance(body.get("changed"), bool):
    raise SystemExit("quote stream check failed: changed is not a boolean")
quotes = body.get("quotes")
if not isinstance(quotes, list) or not quotes:
    raise SystemExit("quote stream check failed: quotes are empty")
quote = quotes[0]
if quote.get("symbol") != expected_symbol:
    raise SystemExit("quote stream check failed: selected symbol is missing")
if quote.get("source_event_id") != expected_source:
    raise SystemExit(
        f"quote stream check failed: source_event_id is {quote.get('source_event_id')!r}, expected {expected_source!r}"
    )
for key, expected in (
    ("event_ts_ms", expected_event_ts),
    ("midpoint_micros", expected_midpoint),
    ("bid_micros", expected_bid),
    ("ask_micros", expected_ask),
):
    value = quote.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value != expected:
        raise SystemExit(f"quote stream check failed: {key} is {value!r}, expected {expected!r}")
PY
printf 'PASS quotes_stream symbol=%s midpoint_micros=%s source_event_id=%s event_ts_ms=%s\n' \
    "$selected_symbol" "$feature_midpoint_micros" "$feature_source_event_id" "$feature_event_ts_ms"

history_window="$tmpdir/history-window.txt"
python3 - "$latest_event_ts_ms" > "$history_window" <<'PY'
import sys
import time
raw_latest = sys.argv[1].strip() if len(sys.argv) > 1 else ""
latest_ms = int(raw_latest) if raw_latest else int(time.time() * 1000)
from_sec = max(0, (latest_ms - 2 * 60 * 60 * 1000) // 1000)
to_sec = (latest_ms + 60 * 1000) // 1000
print(from_sec)
print(to_sec)
PY
history_from="$(sed -n '1p' "$history_window")"
history_to="$(sed -n '2p' "$history_window")"
encoded_history_resolution="$(urlencode "$DEMO_HISTORY_RESOLUTION")"

history_json="$tmpdir/history.json"
fetch_json "/datafeed/history?symbol=${encoded_symbol}&resolution=${encoded_history_resolution}&from=${history_from}&to=${history_to}" "$history_json"
history_bars="$(python3 - "$history_json" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
if body.get("s") != "ok":
    raise SystemExit("datafeed history check failed: status is not ok")
times = body.get("t")
if not isinstance(times, list) or not times:
    raise SystemExit("datafeed history check failed: no bars returned")
print(len(times))
PY
)"
printf 'PASS datafeed_history symbol=%s resolution=%s bars=%s\n' \
    "$selected_symbol" "$DEMO_HISTORY_RESOLUTION" "$history_bars"

config_json="$tmpdir/datafeed-config.json"
fetch_json "/datafeed/config" "$config_json"
python3 - "$config_json" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
if not body.get("supports_search"):
    raise SystemExit("datafeed config check failed: supports_search is false/missing")
resolutions = body.get("supported_resolutions")
if not isinstance(resolutions, list) or not resolutions:
    raise SystemExit("datafeed config check failed: supported_resolutions is empty")
PY
printf 'PASS datafeed_config\n'

printf 'PASS db_primary_demo_smoke base_url=%s symbol=%s feature=%s count=%s history_bars=%s market_metadata_rows=%s\n' \
    "$FRONTEND_BASE_URL" "$selected_symbol" "$DEMO_FEATURE" "$feature_count" "$history_bars" "$market_metadata_rows"
