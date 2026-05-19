#!/bin/sh
set -eu

FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://127.0.0.1:8090}"
FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
EXPECTED_FEATURE_SOURCE="${EXPECTED_FEATURE_SOURCE:-feature_outputs}"
DEMO_FEATURE="${DEMO_FEATURE:-feature.bbo}"
DEMO_LIMIT="${DEMO_LIMIT:-5}"
DEMO_HISTORY_RESOLUTION="${DEMO_HISTORY_RESOLUTION:-1}"
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

health_json="$tmpdir/health.json"
fetch_json "/health" "$health_json"
feature_source="$(python3 - "$health_json" "$EXPECTED_FEATURE_SOURCE" <<'PY'
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
if expected and feature_source != expected:
    raise SystemExit(
        f"health check failed: feature_source is {feature_source!r}, expected {expected!r}; "
        "restart frontend-adapter with FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs"
    )
print(feature_source)
PY
)"
printf 'PASS health service=frontend-adapter feature_source=%s expected_feature_source=%s\n' \
    "$feature_source" "$EXPECTED_FEATURE_SOURCE"

symbols_json="$tmpdir/symbols.json"
fetch_json "/symbols" "$symbols_json"
symbol_selection="$tmpdir/symbol-selection.txt"
python3 - "$symbols_json" "${DEMO_SYMBOL:-}" > "$symbol_selection" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
symbols = body.get("symbols")
if not isinstance(symbols, list) or not symbols:
    raise SystemExit("symbols check failed: /symbols returned no symbols")
override = sys.argv[2].strip() if len(sys.argv) > 2 else ""
selected = override
latest = ""
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

features_json="$tmpdir/features.json"
fetch_json "/features?symbol=${encoded_symbol}&feature=${encoded_feature}&limit=${encoded_limit}" "$features_json"
feature_count="$(python3 - "$features_json" "$selected_symbol" "$DEMO_FEATURE" <<'PY'
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
    if "values" not in output or output["values"] is None:
        raise SystemExit(f"features check failed: output {index} has no values")
print(count)
PY
)"
printf 'PASS features symbol=%s feature=%s count=%s\n' "$selected_symbol" "$DEMO_FEATURE" "$feature_count"

quotes_json="$tmpdir/quotes.json"
fetch_json "/quotes?symbols=${encoded_symbol}" "$quotes_json"
python3 - "$quotes_json" "$selected_symbol" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
quotes = body.get("quotes")
if not isinstance(quotes, list) or not quotes:
    raise SystemExit("quotes check failed: quotes are empty")
if quotes[0].get("symbol") != sys.argv[2]:
    raise SystemExit("quotes check failed: selected symbol is missing")
PY
printf 'PASS quotes symbol=%s\n' "$selected_symbol"

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

printf 'PASS db_primary_demo_smoke base_url=%s symbol=%s feature=%s count=%s history_bars=%s\n' \
    "$FRONTEND_BASE_URL" "$selected_symbol" "$DEMO_FEATURE" "$feature_count" "$history_bars"
