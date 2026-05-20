#!/bin/sh
set -eu

FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://127.0.0.1:8090}"
FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
FRONTEND_BROWSER_SMOKE_VIRTUAL_TIME_MS="${FRONTEND_BROWSER_SMOKE_VIRTUAL_TIME_MS:-8000}"
BROWSER_BIN="${BROWSER_BIN:-}"

find_browser() {
    if [ -n "$BROWSER_BIN" ]; then
        if command -v "$BROWSER_BIN" >/dev/null 2>&1 || [ -x "$BROWSER_BIN" ]; then
            printf '%s\n' "$BROWSER_BIN"
            return 0
        fi
        printf 'configured BROWSER_BIN is not executable or on PATH: %s\n' "$BROWSER_BIN" >&2
        return 1
    fi
    for candidate in chromium chromium-browser google-chrome google-chrome-stable; do
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return 0
        fi
    done
    return 1
}

browser="$(find_browser || true)"
if [ -z "$browser" ]; then
    printf 'frontend browser smoke requires chromium/chrome or BROWSER_BIN; skipping would not validate browser runtime\n' >&2
    exit 2
fi

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/frontend-browser-smoke.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT INT HUP TERM

dom_file="$tmpdir/dashboard.dom.html"
browser_log="$tmpdir/browser.log"

if ! NO_PROXY="$FRONTEND_NO_PROXY" no_proxy="$FRONTEND_NO_PROXY" "$browser" \
    --headless=new \
    --disable-gpu \
    --disable-background-networking \
    --disable-component-update \
    --no-sandbox \
    --no-first-run \
    --disable-dev-shm-usage \
    --window-size=1440,1000 \
    --virtual-time-budget="$FRONTEND_BROWSER_SMOKE_VIRTUAL_TIME_MS" \
    --dump-dom "${FRONTEND_BASE_URL}/" > "$dom_file" 2> "$browser_log"; then
    printf 'frontend browser smoke failed to open dashboard url=%s browser=%s\n' "$FRONTEND_BASE_URL/" "$browser" >&2
    sed -n '1,120p' "$browser_log" >&2 || true
    exit 1
fi

python3 - "$dom_file" "$FRONTEND_BASE_URL" "$browser" <<'PY'
import re
import sys

dom_path, base_url, browser = sys.argv[1:4]
with open(dom_path, "r", encoding="utf-8") as handle:
    html = handle.read()

def require(fragment, label):
    if fragment not in html:
        raise SystemExit(f"browser dashboard missing {label}: {fragment}")

for fragment, label in (
    ("Kalshi Product Dashboard", "title"),
    ('id="market-list"', "market list"),
    ('id="chart-container"', "chart container"),
    ('id="quote-strip"', "quote strip"),
    ('id="feature-list"', "feature list"),
    ('id="health-grid"', "runtime health grid"),
    ('id="adapter-health"', "adapter health"),
    ('id="release-identity"', "release identity"),
    ('id="health-data-age"', "data freshness"),
    ('id="quote-update-health"', "quote update status"),
    ('id="refresh-health"', "refresh health"),
    ('id="featureplant-health"', "featureplant health"),
):
    require(fragment, label)

if "<canvas" not in html.lower():
    raise SystemExit("browser dashboard did not create chart canvas")
if "(loading...)" in html:
    raise SystemExit("browser dashboard still shows initial loading placeholder")
if 'data-symbol=' not in html and "No markets indexed yet" not in html:
    raise SystemExit("browser dashboard did not render market list or empty state")
if 'class="feature-row"' not in html and "no feature outputs" not in html and "No markets indexed yet" not in html:
    raise SystemExit("browser dashboard did not render feature list state")
if not re.search(r'id="freshness-state"[^>]*>\s*(waiting|fresh|stale)\s*<', html):
    raise SystemExit("browser dashboard did not render quote freshness state")
if re.search(r'id="adapter-health"[^>]*>\s*-\s*<', html):
    raise SystemExit("runtime health did not update adapter status")
if re.search(r'id="quote-update-health"[^>]*>\s*-\s*<', html):
    raise SystemExit("quote update status did not render")
quote_status = re.search(r'id="quote-update-health"[^>]*>\s*([^<]+)\s*<', html)
status_text = quote_status.group(1).strip() if quote_status else ""
stream_metrics = re.search(r'SSE req\s+(\d+)\s+/ events\s+(\d+)', status_text)
poll_metrics = re.search(r'long-poll req\s+(\d+)\s+/ changed\s+(\d+)\s+/ timeout\s+(\d+)', status_text)
stream_events = int(stream_metrics.group(2)) if stream_metrics else 0
poll_activity = sum(int(value) for value in poll_metrics.groups()) if poll_metrics else 0
active_message = re.search(
    r'(SSE (connected|snapshot|changed)|long-poll (changed|timeout|fallback)|fallback polling)',
    status_text,
)
if not (active_message or stream_events > 0 or poll_activity > 0):
    raise SystemExit("quote feed status did not show active SSE/fallback traffic")
if re.search(r'id="quote-update-health"[^>]*>\s*(SSE|long-poll) error', html):
    raise SystemExit("quote update status entered an error state")
if "LightweightCharts is not defined" in html:
    raise SystemExit("vendored LightweightCharts asset did not load")

print(f"PASS frontend_browser_smoke url={base_url}/ browser={browser}")
PY
