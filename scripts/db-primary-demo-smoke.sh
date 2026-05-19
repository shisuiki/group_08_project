#!/bin/sh
set -eu

FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://127.0.0.1:8090}"
DEMO_FEATURE="${DEMO_FEATURE:-feature.bbo}"
DEMO_LIMIT="${DEMO_LIMIT:-5}"

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
    curl -fsS "${FRONTEND_BASE_URL}${endpoint}" -o "$output"
    python3 - "$output" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    json.load(handle)
PY
}

health_json="$tmpdir/health.json"
fetch_json "/health" "$health_json"
feature_source="$(python3 - "$health_json" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
if body.get("service") != "frontend-adapter":
    raise SystemExit("health check failed: service is not frontend-adapter")
feature_source = body.get("feature_source")
if not feature_source:
    raise SystemExit("health check failed: feature_source is missing")
print(feature_source)
PY
)"
printf 'PASS health service=frontend-adapter feature_source=%s\n' "$feature_source"

symbols_json="$tmpdir/symbols.json"
fetch_json "/symbols" "$symbols_json"
selected_symbol="$(python3 - "$symbols_json" "${DEMO_SYMBOL:-}" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
symbols = body.get("symbols")
if not isinstance(symbols, list) or not symbols:
    raise SystemExit("symbols check failed: /symbols returned no symbols")
override = sys.argv[2].strip() if len(sys.argv) > 2 else ""
if override:
    print(override)
else:
    first = symbols[0]
    symbol = first.get("symbol") if isinstance(first, dict) else None
    if not symbol:
        raise SystemExit("symbols check failed: first symbol is missing")
    print(symbol)
PY
)"
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

printf 'PASS db_primary_demo_smoke base_url=%s symbol=%s feature=%s count=%s\n' \
    "$FRONTEND_BASE_URL" "$selected_symbol" "$DEMO_FEATURE" "$feature_count"
