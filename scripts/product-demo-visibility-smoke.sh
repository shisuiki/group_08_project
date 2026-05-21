#!/usr/bin/env sh
set -eu

BASE_URL="${PRODUCT_DEMO_BASE_URL:-${FRONTEND_BASE_URL:-http://127.0.0.1:8090}}"
TOP_N="${PRODUCT_DEMO_SMOKE_TOP_N:-20}"
CAPABILITY="${PRODUCT_DEMO_SMOKE_CAPABILITY:-quote_available}"
REQUIRE_LIVE="${PRODUCT_DEMO_SMOKE_REQUIRE_LIVE:-true}"
REQUIRE_REPLAY="${PRODUCT_DEMO_SMOKE_REQUIRE_REPLAY:-true}"
NO_PROXY_TARGETS="${PRODUCT_DEMO_SMOKE_NO_PROXY:-127.0.0.1,localhost}"
AUTH_USER="${FRONTEND_ADAPTER_BASIC_AUTH_USER:-}"
AUTH_PASSWORD="${FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD:-}"

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/product-demo-visibility-smoke.XXXXXX")"
cleanup() {
    rm -rf "$tmpdir"
}
trap cleanup EXIT

curl_json() {
    path="$1"
    if [ -n "$AUTH_USER" ] && [ -n "$AUTH_PASSWORD" ]; then
        curl -fsS --noproxy "$NO_PROXY_TARGETS" --max-time 15 --user "$AUTH_USER:$AUTH_PASSWORD" "$BASE_URL$path"
    else
        curl -fsS --noproxy "$NO_PROXY_TARGETS" --max-time 15 "$BASE_URL$path"
    fi
}

health_json="$tmpdir/health.json"
capabilities_json="$tmpdir/capabilities.json"
history_json="$tmpdir/history.json"
quotes_json="$tmpdir/quotes.json"
updates_json="$tmpdir/updates.json"
replay_json="$tmpdir/replay.json"

curl_json "/health" > "$health_json"
python3 - "$health_json" "$REQUIRE_LIVE" <<'PY'
import json
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
require_live = sys.argv[2].lower() == "true"
if body.get("status") != "ok":
    raise SystemExit(f"health status is {body.get('status')!r}")
freshness = body.get("data_freshness") or {}
if require_live and freshness.get("live_data_observed") is not True:
    raise SystemExit("live_data_observed is not true")
print(
    "PASS product_demo_health "
    f"status={body.get('status')} "
    f"live_data_observed={str(freshness.get('live_data_observed')).lower()} "
    f"source_kind={freshness.get('source_kind') or '-'} "
    f"feature_source={body.get('feature_source') or '-'}"
)
PY

curl_json "/api/markets/capabilities?limit=$TOP_N&capability=$CAPABILITY" > "$capabilities_json"
SYMBOL="$(
    python3 - "$capabilities_json" "$TOP_N" <<'PY'
import json
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
top_n = int(sys.argv[2])
markets = body.get("markets") or []
if not markets:
    raise SystemExit("no capability rows returned")
for index, row in enumerate(markets[:top_n], start=1):
    bars = int(row.get("bars_24h_count") or row.get("history_bars_24h") or 0)
    if bars < 10:
        raise SystemExit(f"row {index} {row.get('market_ticker')} has only {bars} bars")
first = markets[0]
print(first.get("market_ticker") or "")
PY
)"
if [ -z "$SYMBOL" ]; then
    echo "first capability row did not include market_ticker" >&2
    exit 1
fi

ENCODED_SYMBOL="$(python3 - "$SYMBOL" <<'PY'
import sys
from urllib.parse import quote
print(quote(sys.argv[1], safe=""))
PY
)"
now_sec="$(date +%s)"
from_sec="$((now_sec - 86400))"
curl_json "/datafeed/history?symbol=$ENCODED_SYMBOL&resolution=1&from=$from_sec&to=$now_sec" > "$history_json"
python3 - "$history_json" "$SYMBOL" <<'PY'
import json
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
symbol = sys.argv[2]
bars = len(body.get("t") or [])
if body.get("s") != "ok":
    raise SystemExit(f"history for {symbol} returned {body.get('s')}")
if bars < 10:
    raise SystemExit(f"history for {symbol} returned only {bars} bars")
print(f"PASS product_demo_chart_bars symbol={symbol} bars={bars} source={body.get('source') or '-'}")
PY

curl_json "/quotes?symbols=$ENCODED_SYMBOL" > "$quotes_json"
curl_json "/quotes/updates?symbols=$ENCODED_SYMBOL&after=0&timeout_ms=1" > "$updates_json"
python3 - "$health_json" "$quotes_json" "$updates_json" "$SYMBOL" <<'PY'
import json
import sys

health = json.load(open(sys.argv[1], encoding="utf-8"))
quotes = json.load(open(sys.argv[2], encoding="utf-8"))
updates = json.load(open(sys.argv[3], encoding="utf-8"))
symbol = sys.argv[4]
quote_rows = quotes.get("quotes") or []
if not isinstance(quote_rows, list):
    raise SystemExit("quotes response does not include quotes list")
if "sequence" not in updates:
    raise SystemExit("quote updates response does not include sequence")
streams = health.get("quote_streams") or {}
waits = health.get("quote_updates") or {}
print(
    "PASS product_demo_distribution "
    f"symbol={symbol} quotes={len(quote_rows)} "
    f"update_sequence={updates.get('sequence')} "
    f"sse_events={streams.get('events', 0)} "
    f"long_poll_changed={waits.get('changed', 0)}"
)
PY

curl_json "/api/demo/replay/status" > "$replay_json"
python3 - "$replay_json" "$REQUIRE_REPLAY" <<'PY'
import json
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
replay = body.get("replay_demo") or body
require_replay = sys.argv[2].lower() == "true"
canonical = int(replay.get("canonical_event_count") or 0)
features = int(replay.get("feature_output_count") or 0)
if replay.get("status") in (None, "unavailable"):
    raise SystemExit(f"replay status is {replay.get('status')!r}")
if require_replay and (canonical <= 0 or features <= 0):
    raise SystemExit(f"replay dataset not projected: canonical={canonical} features={features}")
print(
    "PASS product_demo_replay "
    f"status={replay.get('status')} "
    f"replay_id={replay.get('replay_id') or '-'} "
    f"canonical={canonical} features={features} "
    f"symbols={len(replay.get('available_symbols') or [])}"
)
PY

printf 'PASS product_demo_visibility_smoke base_url=%s symbol=%s top_n=%s capability=%s\n' "$BASE_URL" "$SYMBOL" "$TOP_N" "$CAPABILITY"
