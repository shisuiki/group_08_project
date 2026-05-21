#!/bin/sh
set -eu

FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://127.0.0.1:8090}"
FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
FRONTEND_BROWSER_SMOKE_VIRTUAL_TIME_MS="${FRONTEND_BROWSER_SMOKE_VIRTUAL_TIME_MS:-8000}"
FRONTEND_BROWSER_SMOKE_DOCKER_ENABLED="${FRONTEND_BROWSER_SMOKE_DOCKER_ENABLED:-false}"
FRONTEND_BROWSER_SMOKE_DOCKER_PREFER="${FRONTEND_BROWSER_SMOKE_DOCKER_PREFER:-false}"
FRONTEND_BROWSER_SMOKE_DOCKER_SUDO="${FRONTEND_BROWSER_SMOKE_DOCKER_SUDO:-false}"
FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE="${FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE:-mcr.microsoft.com/playwright:v1.49.1-jammy}"
FRONTEND_BROWSER_SMOKE_DOCKER_NETWORK="${FRONTEND_BROWSER_SMOKE_DOCKER_NETWORK:-host}"
FRONTEND_BROWSER_SMOKE_TIMEOUT_SECONDS="${FRONTEND_BROWSER_SMOKE_TIMEOUT_SECONDS:-60}"
FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE="${FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE:-}"
FRONTEND_BROWSER_SMOKE_OUTPUT_DIR="${FRONTEND_BROWSER_SMOKE_OUTPUT_DIR:-}"
FRONTEND_BROWSER_SMOKE_EXPECTED_HISTORY_BARS_MIN="${FRONTEND_BROWSER_SMOKE_EXPECTED_HISTORY_BARS_MIN:-0}"
FRONTEND_ADAPTER_BASIC_AUTH_USER="${FRONTEND_ADAPTER_BASIC_AUTH_USER:-}"
FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD="${FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD:-}"
BROWSER_BIN="${BROWSER_BIN:-}"
SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
CDP_SMOKE_SCRIPT="$SCRIPT_DIR/frontend-browser-cdp-smoke.py"

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/frontend-browser-smoke.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT INT HUP TERM

artifact_dir="$FRONTEND_BROWSER_SMOKE_OUTPUT_DIR"
if [ -z "$artifact_dir" ] && [ -n "$FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE" ]; then
    artifact_dir="$(dirname "$FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE")"
fi
if [ -z "$artifact_dir" ]; then
    artifact_dir="$tmpdir"
fi
mkdir -p "$artifact_dir"
artifact_dir="$(CDPATH= cd "$artifact_dir" && pwd)"
dom_file="${FRONTEND_BROWSER_SMOKE_DOM_FILE:-${artifact_dir}/dashboard.dom.html}"
screenshot_file="${FRONTEND_BROWSER_SMOKE_SCREENSHOT_FILE:-${artifact_dir}/dashboard.png}"
profile_dir="${artifact_dir}/chrome-profile"
browser_log="${FRONTEND_BROWSER_SMOKE_LOG_FILE:-${artifact_dir}/browser.log}"

absolute_path() {
    case "$1" in
        /*) printf '%s\n' "$1" ;;
        *) printf '%s/%s\n' "$(pwd)" "$1" ;;
    esac
}

dom_file="$(absolute_path "$dom_file")"
screenshot_file="$(absolute_path "$screenshot_file")"
browser_log="$(absolute_path "$browser_log")"
mkdir -p "$(dirname "$dom_file")" "$(dirname "$screenshot_file")" "$profile_dir"

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

docker_browser_enabled() {
    case "$(printf '%s' "$FRONTEND_BROWSER_SMOKE_DOCKER_ENABLED" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|y|on) return 0 ;;
        *) return 1 ;;
    esac
}

docker_browser_preferred() {
    case "$(printf '%s' "$FRONTEND_BROWSER_SMOKE_DOCKER_PREFER" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|y|on) return 0 ;;
        *) return 1 ;;
    esac
}

docker_browser_sudo_enabled() {
    case "$(printf '%s' "$FRONTEND_BROWSER_SMOKE_DOCKER_SUDO" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|y|on) return 0 ;;
        *) return 1 ;;
    esac
}

docker_cmd() {
    if docker_browser_sudo_enabled; then
        sudo docker "$@"
    else
        docker "$@"
    fi
}

docker_cmd_with_timeout() {
    if command -v timeout >/dev/null 2>&1; then
        if docker_browser_sudo_enabled; then
            timeout "$FRONTEND_BROWSER_SMOKE_TIMEOUT_SECONDS" sudo docker "$@"
        else
            timeout "$FRONTEND_BROWSER_SMOKE_TIMEOUT_SECONDS" docker "$@"
        fi
    else
        docker_cmd "$@"
    fi
}

write_skip_evidence() {
    reason="$1"
    mode="$2"
    if [ -z "$FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE" ]; then
        return 0
    fi
    python3 - "$FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE" "$FRONTEND_BASE_URL" "$mode" "$reason" \
        "$FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE" <<'PY'
import json
import sys
from pathlib import Path

evidence_path, base_url, mode, reason, docker_image = sys.argv[1:6]
body = {
    "schema_version": 1,
    "evidence_type": "frontend_browser_smoke",
    "status": "skipped",
    "mode": mode,
    "reason": reason,
    "dashboard_url": base_url.rstrip("/") + "/",
    "docker_image": docker_image if mode == "docker" else None,
    "dom_path": None,
    "dom_sha256": None,
    "screenshot_path": None,
    "screenshot_sha256": None,
    "checks": {},
}
Path(evidence_path).parent.mkdir(parents=True, exist_ok=True)
with open(evidence_path, "w", encoding="utf-8") as handle:
    json.dump(body, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY
}

write_failure_evidence() {
    reason="$1"
    mode="$2"
    exit_code="$3"
    browser_value="${4:-}"
    if [ -z "$FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE" ]; then
        return 0
    fi
    python3 - "$FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE" "$FRONTEND_BASE_URL" "$mode" "$reason" "$exit_code" \
        "$FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE" "$browser_value" "$dom_file" "$screenshot_file" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

(
    evidence_path,
    base_url,
    mode,
    reason,
    exit_code,
    docker_image,
    browser,
    dom_path,
    screenshot_path,
) = sys.argv[1:10]

def maybe_hash(path):
    target = Path(path)
    if not target.is_file() or target.stat().st_size <= 0:
        return None
    digest = hashlib.sha256()
    with open(target, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()

dom_sha256 = maybe_hash(dom_path)
screenshot_sha256 = maybe_hash(screenshot_path)
body = {
    "schema_version": 1,
    "evidence_type": "frontend_browser_smoke",
    "status": "failed",
    "mode": mode,
    "reason": reason,
    "exit_code": int(exit_code),
    "dashboard_url": base_url.rstrip("/") + "/",
    "browser": browser or None,
    "docker_image": docker_image if mode == "docker" else None,
    "dom_path": dom_path if dom_sha256 else None,
    "dom_sha256": dom_sha256,
    "screenshot_path": screenshot_path if screenshot_sha256 else None,
    "screenshot_sha256": screenshot_sha256,
    "checks": {},
}
Path(evidence_path).parent.mkdir(parents=True, exist_ok=True)
with open(evidence_path, "w", encoding="utf-8") as handle:
    json.dump(body, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY
}

run_host_browser() {
    runner=""
    if command -v timeout >/dev/null 2>&1; then
        runner="timeout $FRONTEND_BROWSER_SMOKE_TIMEOUT_SECONDS"
    fi
    NO_PROXY="$FRONTEND_NO_PROXY" no_proxy="$FRONTEND_NO_PROXY" $runner \
        python3 "$CDP_SMOKE_SCRIPT" \
            --browser "$browser" \
            --url "${FRONTEND_BASE_URL}/" \
            --dom-file "$dom_file" \
            --screenshot-file "$screenshot_file" \
            --browser-log "$browser_log" \
            --profile-dir "$profile_dir" \
            --timeout-seconds "$FRONTEND_BROWSER_SMOKE_TIMEOUT_SECONDS" > "$tmpdir/cdp-result.json"
}

run_docker_browser() {
    docker_cmd_with_timeout run --rm \
        --network "$FRONTEND_BROWSER_SMOKE_DOCKER_NETWORK" \
        --user "$(id -u):$(id -g)" \
        -e NO_PROXY="$FRONTEND_NO_PROXY" \
        -e no_proxy="$FRONTEND_NO_PROXY" \
        -e FRONTEND_BROWSER_SMOKE_VIRTUAL_TIME_MS="$FRONTEND_BROWSER_SMOKE_VIRTUAL_TIME_MS" \
        -e FRONTEND_BROWSER_SMOKE_EXPECTED_HISTORY_BARS_MIN="$FRONTEND_BROWSER_SMOKE_EXPECTED_HISTORY_BARS_MIN" \
        -e FRONTEND_ADAPTER_BASIC_AUTH_USER="$FRONTEND_ADAPTER_BASIC_AUTH_USER" \
        -e FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD="$FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD" \
        -v "$CDP_SMOKE_SCRIPT:$CDP_SMOKE_SCRIPT:ro" \
        -v "$artifact_dir:$artifact_dir" \
        --entrypoint /bin/sh \
        "$FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE" \
        -c '
set -eu
cdp_script="$1"
dashboard_url="$2"
dom_file="$3"
screenshot_file="$4"
browser_log="$5"
profile_dir="$6"
timeout_seconds="$7"
browser=""
for candidate in chromium chromium-browser google-chrome google-chrome-stable /usr/bin/chromium /usr/bin/chromium-browser /usr/bin/google-chrome /usr/bin/google-chrome-stable /ms-playwright/chromium-*/chrome-linux/chrome; do
    for resolved in $candidate; do
        if command -v "$resolved" >/dev/null 2>&1; then
            browser="$(command -v "$resolved")"
            break 2
        fi
        if [ -x "$resolved" ]; then
            browser="$resolved"
            break 2
        fi
    done
done
if [ -z "$browser" ]; then
    printf "no chromium/chrome binary found in docker browser image\n" >&2
    exit 127
fi
printf "docker browser binary: %s\n" "$browser" >&2
exec python3 "$cdp_script" \
    --browser "$browser" \
    --url "$dashboard_url" \
    --dom-file "$dom_file" \
    --screenshot-file "$screenshot_file" \
    --browser-log "$browser_log" \
    --profile-dir "$profile_dir" \
    --timeout-seconds "$timeout_seconds"
' sh "$CDP_SMOKE_SCRIPT" "${FRONTEND_BASE_URL}/" "$dom_file" "$screenshot_file" "$browser_log" "$profile_dir" \
        "$FRONTEND_BROWSER_SMOKE_TIMEOUT_SECONDS" > "$tmpdir/cdp-result.json" 2>> "$browser_log"
}

browser=""
if ! docker_browser_enabled || ! docker_browser_preferred; then
    browser="$(find_browser || true)"
fi
browser_mode="host"
if [ -z "$browser" ]; then
    browser_mode="none"
    if docker_browser_enabled; then
        browser_mode="docker"
        if ! command -v docker >/dev/null 2>&1; then
            write_skip_evidence "missing_browser" "$browser_mode"
            printf 'SKIP browser_smoke reason=missing_browser mode=docker detail=docker_unavailable\n'
            exit 2
        fi
        if ! docker_cmd image inspect "$FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE" >/dev/null 2>&1; then
            if ! docker_cmd_with_timeout pull "$FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE" >/dev/null 2> "$browser_log"; then
                write_skip_evidence "missing_browser" "$browser_mode"
                printf 'SKIP browser_smoke reason=missing_browser mode=docker detail=image_unavailable image=%s\n' \
                    "$FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE"
                sed -n '1,80p' "$browser_log" >&2 || true
                exit 2
            fi
        fi
        browser="docker:${FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE}"
    else
        write_skip_evidence "missing_browser" "$browser_mode"
        printf 'frontend browser smoke requires chromium/chrome or BROWSER_BIN; skipping would not validate browser runtime\n' >&2
        exit 2
    fi
fi

if [ "$browser_mode" = "docker" ]; then
    run_browser_result=0
    run_docker_browser || run_browser_result=$?
else
    run_browser_result=0
    run_host_browser || run_browser_result=$?
fi

if [ "$run_browser_result" -ne 0 ]; then
    write_failure_evidence "browser_runtime_failed" "$browser_mode" "$run_browser_result" "$browser"
    printf 'frontend browser smoke failed to open dashboard url=%s browser=%s\n' "$FRONTEND_BASE_URL/" "$browser" >&2
    sed -n '1,120p' "$browser_log" >&2 || true
    exit 1
fi

if ! python3 - "$dom_file" "$screenshot_file" "$FRONTEND_BASE_URL" "$browser" "$browser_mode" \
    "$FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE" "$FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE" \
    "$tmpdir/cdp-result.json" "$FRONTEND_BROWSER_SMOKE_EXPECTED_HISTORY_BARS_MIN" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

(
    dom_path,
    screenshot_path,
    base_url,
    browser,
    browser_mode,
    evidence_path,
    docker_image,
    cdp_result_path,
    expected_history_bars_raw,
) = sys.argv[1:10]
with open(dom_path, "r", encoding="utf-8") as handle:
    html = handle.read()
with open(cdp_result_path, "r", encoding="utf-8") as handle:
    cdp_result = json.load(handle)
cdp_state = cdp_result.get("state") or {}
expected_history_bars = int(expected_history_bars_raw or "0")
history_bars = int(cdp_state.get("historyBars") or 0)

def require(fragment, label):
    if fragment not in html:
        raise SystemExit(f"browser dashboard missing {label}: {fragment}")

for fragment, label in (
    ("Kalshi Product Dashboard", "title"),
    ('id="market-list"', "market list"),
    ('id="market-search"', "market search"),
    ('id="market-capability-filter"', "market capability filter"),
    ('id="market-status-filter"', "market status filter"),
    ('id="market-page-state"', "market pagination state"),
    ('id="market-state"', "market filter state"),
    ('id="chart-container"', "chart container"),
    ('id="chart-state"', "chart capability state"),
    ('id="quote-strip"', "quote strip"),
    ('id="feature-list"', "feature list"),
    ('id="health-grid"', "runtime health grid"),
    ('id="adapter-health"', "adapter health"),
    ('id="release-identity"', "release identity"),
    ('id="health-data-age"', "data freshness"),
    ('id="quote-update-health"', "quote update status"),
    ('id="refresh-health"', "refresh health"),
    ('id="featureplant-health"', "featureplant health"),
    ('id="product-market-panel"', "product readiness panel"),
    ('id="overview-tab"', "overview role tab"),
    ('id="markets-tab"', "markets role tab"),
    ('id="chart-tab"', "chart role tab"),
    ('id="semantic-tab"', "semantic role tab"),
    ('id="research-tab"', "research role tab"),
    ('id="operator-tab"', "operator role tab"),
    ('id="trader-monitor-panel"', "trader quote monitor"),
    ('id="trader-bid"', "trader bid"),
    ('id="trader-sse-status"', "trader sse status"),
    ('id="product-readiness-state"', "product readiness state"),
    ('id="research-features-panel"', "research features panel"),
    ('id="research-feature-select"', "research feature selector"),
    ('id="research-feature-limit"', "research feature limit"),
    ('id="research-feature-window"', "research feature window"),
    ('id="research-export-csv"', "research csv export"),
    ('id="runtime-operator-panel"', "runtime operator panel"),
    ('id="runtime-feature-source"', "runtime feature source"),
    ('id="runtime-pipeline-status"', "runtime pipeline status"),
    ('id="runtime-cursor-lag"', "runtime cursor lag"),
    ('id="runtime-quote-streams"', "runtime quote streams"),
    ('id="runtime-quote-waits"', "runtime quote waits"),
    ('id="latency-freshness-panel"', "latency freshness panel"),
    ('id="freshness-age-ms"', "freshness age"),
    ('id="operator-plan-panel"', "operator plan panel"),
    ('id="operator-plan-state"', "operator plan state"),
    ('id="operator-db-status"', "operator db status"),
    ('id="operator-kalshi-status"', "operator kalshi status"),
    ('id="operator-auth-status"', "operator auth status"),
    ('id="operator-data-source"', "operator data source"),
    ('id="operator-e2e-latency"', "operator e2e latency"),
    ('id="operator-pipeline-counts"', "operator pipeline counts"),
    ('id="operator-generate-plan"', "operator plan generator"),
    ('id="operator-env-plan"', "operator plan output"),
):
    require(fragment, label)

for key, label in (
    ("runtimePipelineStatus", "ops pipeline API"),
    ("operatorE2eLatency", "ops latency API"),
):
    value = str(cdp_state.get(key) or "").strip()
    if value in ("", "-"):
        raise SystemExit(f"browser dashboard missing {label}: {key}")

if "<canvas" not in html.lower():
    raise SystemExit("browser dashboard did not create chart canvas")
if "(loading...)" in html:
    raise SystemExit("browser dashboard still shows initial loading placeholder")
if 'data-symbol=' not in html and "No markets indexed yet" not in html and "No markets match" not in html:
    raise SystemExit("browser dashboard did not render market list or empty state")
if (
    'class="feature-row"' not in html
    and "no feature outputs" not in html
    and "No markets indexed yet" not in html
    and "No markets match" not in html
):
    raise SystemExit("browser dashboard did not render feature list state")
if not re.search(r'id="freshness-state"[^>]*>\s*(waiting|fresh|stale)\s*<', html):
    raise SystemExit("browser dashboard did not render quote freshness state")
if re.search(r'id="adapter-health"[^>]*>\s*-\s*<', html):
    raise SystemExit("runtime health did not update adapter status")
if re.search(r'id="release-identity"[^>]*>\s*-\s*<', html):
    raise SystemExit("release identity did not render")
if re.search(r'id="health-data-age"[^>]*>\s*-\s*<', html):
    raise SystemExit("data freshness did not render")
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
if expected_history_bars > 0 and history_bars < expected_history_bars:
    raise SystemExit(
        f"browser dashboard rendered {history_bars} history bars, expected at least {expected_history_bars}"
    )
if cdp_state.get("noHorizontalOverflow") is not True:
    raise SystemExit("browser dashboard has horizontal overflow")

def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()

dom_sha256 = sha256(dom_path)
screenshot_exists = Path(screenshot_path).is_file() and Path(screenshot_path).stat().st_size > 0
screenshot_sha256 = sha256(screenshot_path) if screenshot_exists else None
checks = {
    "market_controls_rendered": all(fragment in html for fragment in (
        'id="market-search"',
        'id="market-status-filter"',
        'id="market-state"',
    )),
    "market_rows": html.count("data-symbol="),
    "market_empty_state": "No markets indexed yet" in html or "No markets match" in html,
    "chart_canvas": "<canvas" in html.lower(),
    "quote_strip_not_loading": "(loading...)" not in html,
    "release_rendered": not re.search(r'id="release-identity"[^>]*>\s*-\s*<', html),
    "freshness_rendered": not re.search(r'id="health-data-age"[^>]*>\s*-\s*<', html),
    "quote_feed_visible": bool(active_message or stream_events > 0 or poll_activity > 0),
    "product_panels_rendered": all(fragment in html for fragment in (
        'id="product-market-panel"',
        'id="trader-monitor-panel"',
        'id="research-features-panel"',
        'id="runtime-operator-panel"',
        'id="latency-freshness-panel"',
        'id="operator-plan-panel"',
    )),
    "history_bars": history_bars,
    "expected_history_bars_min": expected_history_bars,
    "no_horizontal_overflow": cdp_state.get("noHorizontalOverflow") is True,
}
if evidence_path:
    body = {
        "schema_version": 1,
        "evidence_type": "frontend_browser_smoke",
        "status": "passed",
        "mode": browser_mode,
        "reason": None,
        "dashboard_url": base_url.rstrip("/") + "/",
        "browser": browser,
        "docker_image": docker_image if browser_mode == "docker" else None,
        "dom_path": dom_path,
        "dom_sha256": dom_sha256,
        "screenshot_path": screenshot_path if screenshot_exists else None,
        "screenshot_sha256": screenshot_sha256,
        "checks": checks,
    }
    Path(evidence_path).parent.mkdir(parents=True, exist_ok=True)
    with open(evidence_path, "w", encoding="utf-8") as handle:
        json.dump(body, handle, indent=2, sort_keys=True)
        handle.write("\n")

evidence_suffix = f" evidence={evidence_path}" if evidence_path else ""
screenshot_suffix = f" screenshot_sha256={screenshot_sha256}" if screenshot_sha256 else ""
print(
    f"PASS frontend_browser_smoke url={base_url.rstrip('/')}/ "
    f"mode={browser_mode} browser={browser} dom_sha256={dom_sha256}"
    f"{screenshot_suffix}{evidence_suffix}"
)
PY
then
    write_failure_evidence "browser_validation_failed" "$browser_mode" 1 "$browser"
    exit 1
fi
