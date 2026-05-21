#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

usage() {
    cat >&2 <<'EOF'
Usage:
  scripts/live-product-rehearsal-up.sh
  scripts/live-product-rehearsal-up.sh --down <evidence-json-or-compose-project>

Starts an isolated live-product-local-db stack with real Kalshi credentials,
runs the strict live-product smoke with live data required, leaves the stack
running on success, and writes non-secret evidence under .demo-state/.
EOF
}

truthy() {
    case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|y|on) return 0 ;;
        *) return 1 ;;
    esac
}

docker_compose() {
    if truthy "${LIVE_PRODUCT_REHEARSAL_DOCKER_SUDO:-false}"; then
        sudo docker compose "$@"
    else
        docker compose "$@"
    fi
}

extract_down_target() {
    target="$1"
    default_profile="${2:-live-product-local-db}"
    if [ -f "$target" ]; then
        python3 - "$target" "$default_profile" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
print(body.get("compose_project_name") or "")
print(body.get("compose_profile") or sys.argv[2])
PY
    else
        printf '%s\n%s\n' "$target" "$default_profile"
    fi
}

safe_project_name() {
    case "$1" in
        group_08_project|kalshi|default|"")
            return 1
            ;;
        *[!A-Za-z0-9_.-]*)
            return 1
            ;;
        *)
            return 0
            ;;
    esac
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    usage
    exit 0
fi

if [ "${1:-}" = "--down" ]; then
    if [ "$#" -ne 2 ]; then
        usage
        exit 2
    fi
    cd "$REPO_ROOT"
    down_target="$(extract_down_target "$2" "${COMPOSE_PROFILE:-live-product-local-db}")"
    project="$(printf '%s\n' "$down_target" | sed -n '1p')"
    compose_profile="$(printf '%s\n' "$down_target" | sed -n '2p')"
    if ! safe_project_name "$project"; then
        printf 'refusing to clean non-isolated compose project: %s\n' "$project" >&2
        exit 2
    fi
    COMPOSE_PROJECT_NAME="$project" docker_compose --profile "$compose_profile" down --remove-orphans -v
    printf 'PASS live_product_rehearsal_down project=%s profile=%s\n' "$project" "$compose_profile"
    exit 0
fi

if [ "$#" -ne 0 ]; then
    usage
    exit 2
fi

cd "$REPO_ROOT"

PROJECT_PREFIX="${LIVE_PRODUCT_REHEARSAL_PROJECT_PREFIX:-kalshi_live_product_rehearsal}"
project="${LIVE_PRODUCT_REHEARSAL_PROJECT_NAME:-${PROJECT_PREFIX}_$(date -u +%Y%m%d%H%M%S)_$$}"
if ! safe_project_name "$project"; then
    printf 'refusing to use non-isolated compose project: %s\n' "$project" >&2
    exit 2
fi

choose_free_port() {
    python3 - "$@" <<'PY'
import os
import socket
import sys

used = {int(value) for value in sys.argv[1:] if value}
start = int(os.environ.get("LIVE_PRODUCT_REHEARSAL_PORT_SCAN_START", "10000"))
end = int(os.environ.get("LIVE_PRODUCT_REHEARSAL_PORT_SCAN_END", "32000"))
for port in range(start, end + 1):
    if port in used:
        continue
    sock = socket.socket()
    try:
        sock.bind(("127.0.0.1", port))
    finally:
        sock.close()
    print(port)
    raise SystemExit(0)
raise SystemExit(f"failed to allocate an unused localhost port in {start}-{end}")
PY
}

choose_free_port_range() {
    python3 - "$@" <<'PY'
import os
import socket
import sys

count = int(sys.argv[1])
used = {int(value) for value in sys.argv[2:] if value}
if count < 1:
    raise SystemExit("port range count must be positive")

scan_start = int(os.environ.get("LIVE_PRODUCT_REHEARSAL_PORT_SCAN_START", "10000"))
scan_end = int(os.environ.get("LIVE_PRODUCT_REHEARSAL_PORT_SCAN_END", "32000"))

for start in range(scan_start, scan_end - count + 2):
    end = start + count - 1
    if any(port in used for port in range(start, end + 1)):
        continue
    sockets = []
    try:
        for port in range(start, end + 1):
            sock = socket.socket()
            sock.bind(("127.0.0.1", port))
            sockets.append(sock)
        print(f"{start}-{end}")
        raise SystemExit(0)
    except OSError:
        pass
    finally:
        for sock in sockets:
            sock.close()

raise SystemExit(f"failed to allocate an unused localhost port range in {scan_start}-{scan_end}")
PY
}

range_ports() {
    range="$1"
    start="${range%-*}"
    end="${range#*-}"
    if [ "$start" = "$end" ]; then
        printf '%s\n' "$start"
        return 0
    fi
    port="$start"
    while [ "$port" -le "$end" ]; do
        printf '%s\n' "$port"
        port=$((port + 1))
    done
}

choose_cluster_subnet() {
    python3 <<'PY'
import ipaddress
import json
import subprocess
import sys

used = []
try:
    ids = subprocess.check_output(["docker", "network", "ls", "-q"], text=True).split()
    if ids:
        output = subprocess.check_output(["docker", "network", "inspect", *ids], text=True)
        for network in json.loads(output):
            for config in (network.get("IPAM") or {}).get("Config") or []:
                subnet = config.get("Subnet")
                if subnet:
                    used.append(ipaddress.ip_network(subnet, strict=False))
except Exception:
    pass

candidates = [f"172.{index}.0.0/16" for index in range(16, 32)]
candidates += [f"10.{index}.0.0/16" for index in range(200, 256)]
for raw in candidates:
    candidate = ipaddress.ip_network(raw)
    if all(not candidate.overlaps(existing) for existing in used):
        print(candidate)
        raise SystemExit(0)
print("could not find a non-overlapping Docker subnet candidate", file=sys.stderr)
raise SystemExit(1)
PY
}

derive_cluster_ip_range() {
    python3 - "$1" <<'PY'
import ipaddress
import sys

network = ipaddress.ip_network(sys.argv[1], strict=False)
if network.prefixlen > 17:
    print(network)
else:
    print(list(network.subnets(new_prefix=17))[1])
PY
}

cluster_ips_from_subnet() {
    python3 - "$1" <<'PY'
import ipaddress
import sys

network = ipaddress.ip_network(sys.argv[1], strict=False)
hosts = list(network.hosts())
if len(hosts) < 8:
    raise SystemExit("cluster subnet must have at least eight usable host addresses")
names = [
    "CLUSTER_NODE0_IP",
    "CLUSTER_NODE1_IP",
    "CLUSTER_NODE2_IP",
    "WSCLIENT_IP",
    "STREAM_TAP_IP",
]
positions = [1, 2, 3, 5, 6]
for name, position in zip(names, positions):
    print(f"{name}={hosts[position]}")
print(f"CLUSTER_ADDRESSES={hosts[1]},{hosts[2]},{hosts[3]}")
PY
}

print_redacted_log() {
    log_file="$1"
    max_lines="${2:-160}"
    KALSHI_KEY_ID="${KALSHI_KEY_ID:-}" \
    KALSHI_KEY_HOST_PATH="${KALSHI_KEY_HOST_PATH:-}" \
    KALSHI_KEY_PATH="${KALSHI_KEY_PATH:-}" \
    DB_WRITER_DATABASE_PASSWORD="${local_db_password:-${DB_WRITER_DATABASE_PASSWORD:-}}" \
    FEATUREPLANT_DB_PASSWORD="${local_db_password:-${FEATUREPLANT_DB_PASSWORD:-}}" \
    FRONTEND_ADAPTER_DB_PASSWORD="${local_db_password:-${FRONTEND_ADAPTER_DB_PASSWORD:-}}" \
    LIVE_PRODUCT_SMOKE_DB_PASSWORD="${local_db_password:-${LIVE_PRODUCT_SMOKE_DB_PASSWORD:-}}" \
    LOCAL_DB_PASSWORD="${local_db_password:-${LOCAL_DB_PASSWORD:-}}" \
    FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD="${auth_password:-${FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD:-}}" \
        python3 - "$log_file" "$max_lines" <<'PY'
import os
import re
import sys
from pathlib import Path

path = sys.argv[1]
max_lines = int(sys.argv[2])
secret_names = (
    "KALSHI_KEY_ID",
    "KALSHI_KEY_HOST_PATH",
    "KALSHI_KEY_PATH",
    "KALSHI_PRIVATE_KEY",
    "DB_WRITER_DATABASE_PASSWORD",
    "FEATUREPLANT_DB_PASSWORD",
    "FRONTEND_ADAPTER_DB_PASSWORD",
    "LIVE_PRODUCT_SMOKE_DB_PASSWORD",
    "LOCAL_DB_PASSWORD",
    "FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD",
)
replacements = []
for name in secret_names:
    value = os.environ.get(name, "")
    if len(value) >= 4:
        replacements.append((value, f"<redacted:{name}>"))
content = Path(path).read_text(encoding="utf-8", errors="replace")
content = re.sub(
    r"-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
    "<redacted:PRIVATE_KEY>",
    content,
    flags=re.DOTALL,
)
for value, replacement in replacements:
    content = content.replace(value, replacement)
for index, line in enumerate(content.splitlines(keepends=True)):
    if index >= max_lines:
        print(f"... redacted preview truncated at {max_lines} lines ...")
        break
    print(line, end="")
PY
}

redact_log() {
    input_file="$1"
    output_file="$2"
    KALSHI_KEY_ID="${KALSHI_KEY_ID:-}" \
    KALSHI_KEY_HOST_PATH="${KALSHI_KEY_HOST_PATH:-}" \
    KALSHI_KEY_PATH="${KALSHI_KEY_PATH:-}" \
    DB_WRITER_DATABASE_PASSWORD="${local_db_password:-${DB_WRITER_DATABASE_PASSWORD:-}}" \
    FEATUREPLANT_DB_PASSWORD="${local_db_password:-${FEATUREPLANT_DB_PASSWORD:-}}" \
    FRONTEND_ADAPTER_DB_PASSWORD="${local_db_password:-${FRONTEND_ADAPTER_DB_PASSWORD:-}}" \
    LIVE_PRODUCT_SMOKE_DB_PASSWORD="${local_db_password:-${LIVE_PRODUCT_SMOKE_DB_PASSWORD:-}}" \
    LOCAL_DB_PASSWORD="${local_db_password:-${LOCAL_DB_PASSWORD:-}}" \
    FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD="${auth_password:-${FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD:-}}" \
        python3 - "$input_file" "$output_file" <<'PY'
import os
import re
import sys
from pathlib import Path

input_path = sys.argv[1]
output_path = sys.argv[2]
secret_names = (
    "KALSHI_KEY_ID",
    "KALSHI_KEY_HOST_PATH",
    "KALSHI_KEY_PATH",
    "KALSHI_PRIVATE_KEY",
    "DB_WRITER_DATABASE_PASSWORD",
    "FEATUREPLANT_DB_PASSWORD",
    "FRONTEND_ADAPTER_DB_PASSWORD",
    "LIVE_PRODUCT_SMOKE_DB_PASSWORD",
    "LOCAL_DB_PASSWORD",
    "FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD",
)
replacements = []
for name in secret_names:
    value = os.environ.get(name, "")
    if len(value) >= 4:
        replacements.append((value, f"<redacted:{name}>"))
Path(output_path).parent.mkdir(parents=True, exist_ok=True)
content = Path(input_path).read_text(encoding="utf-8", errors="replace")
content = re.sub(
    r"-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
    "<redacted:PRIVATE_KEY>",
    content,
    flags=re.DOTALL,
)
for value, replacement in replacements:
    content = content.replace(value, replacement)
Path(output_path).write_text(content, encoding="utf-8")
PY
    chmod 600 "$output_file"
}

wait_frontend_health() {
    attempts="${SMOKE_HTTP_ATTEMPTS:-90}"
    sleep_seconds="${SMOKE_HTTP_RETRY_SLEEP_SECONDS:-1}"
    attempt=0
    while [ "$attempt" -lt "$attempts" ]; do
        attempt=$((attempt + 1))
        if curl -fsS --noproxy "${FRONTEND_NO_PROXY:-127.0.0.1,localhost}" \
            --user "${auth_user}:${auth_password}" \
            "$frontend_health_url" >/dev/null 2>&1; then
            return 0
        fi
        sleep "$sleep_seconds"
    done
    return 1
}

require_readable_file() {
    label="$1"
    path="$2"
    if [ -z "$path" ]; then
        printf '%s is required for live rehearsal\n' "$label" >&2
        exit 2
    fi
    if [ ! -r "$path" ]; then
        printf '%s is not readable; set a readable host private key file\n' "$label" >&2
        exit 2
    fi
}

KALSHI_KEY_ID="${KALSHI_KEY_ID:-}"
KALSHI_KEY_HOST_PATH="${KALSHI_KEY_HOST_PATH:-./secrets/kalshi_private_key}"
KALSHI_KEY_PATH="${KALSHI_KEY_PATH:-/run/secrets/kalshi_private_key}"
if [ -z "$KALSHI_KEY_ID" ]; then
    printf 'KALSHI_KEY_ID is required for live rehearsal\n' >&2
    exit 2
fi
require_readable_file KALSHI_KEY_HOST_PATH "$KALSHI_KEY_HOST_PATH"

if [ -n "${KALSHI_MARKET_TICKERS:-}" ]; then
    market_selection_mode="${KALSHI_MARKET_SELECTION_MODE:-configured}"
elif [ -n "${KALSHI_MARKET_SERIES_TICKER:-}" ]; then
    market_selection_mode="${KALSHI_MARKET_SELECTION_MODE:-configured}"
else
    market_selection_mode="${KALSHI_MARKET_SELECTION_MODE:-open}"
fi
market_discovery_max="${KALSHI_MARKET_DISCOVERY_MAX_MARKETS:-3}"
market_discovery_limit="${KALSHI_MARKET_DISCOVERY_LIMIT:-50}"

compose_profile="${COMPOSE_PROFILE:-live-product-local-db}"
if [ "$compose_profile" != "live-product-local-db" ]; then
    printf 'live rehearsal requires COMPOSE_PROFILE=live-product-local-db, got %s\n' "$compose_profile" >&2
    exit 2
fi

postgres_port="${POSTGRES_HOST_PORT:-$(choose_free_port)}"
frontend_port="${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT:-$(choose_free_port "$postgres_port")}"
featureplant_port="${FEATUREPLANT_METRICS_HOST_PORT:-$(choose_free_port "$postgres_port" "$frontend_port")}"
wsclient_port="${WSCLIENT_METRICS_HOST_PORT:-$(choose_free_port "$postgres_port" "$frontend_port" "$featureplant_port")}"
streamtap_port="${STREAM_TAP_HOST_PORT:-$(choose_free_port "$postgres_port" "$frontend_port" "$featureplant_port" "$wsclient_port")}"
node0_range="${NODE0_CLUSTER_PORT_RANGE:-$(choose_free_port_range 6 "$postgres_port" "$frontend_port" "$featureplant_port" "$wsclient_port" "$streamtap_port")}"
node1_range="${NODE1_CLUSTER_PORT_RANGE:-$(choose_free_port_range 6 "$postgres_port" "$frontend_port" "$featureplant_port" "$wsclient_port" "$streamtap_port" $(range_ports "$node0_range"))}"
node2_range="${NODE2_CLUSTER_PORT_RANGE:-$(choose_free_port_range 6 "$postgres_port" "$frontend_port" "$featureplant_port" "$wsclient_port" "$streamtap_port" $(range_ports "$node0_range") $(range_ports "$node1_range"))}"
node0_aeron_port="${NODE0_AERON_PORT:-$(choose_free_port "$postgres_port" "$frontend_port" "$featureplant_port" "$wsclient_port" "$streamtap_port" $(range_ports "$node0_range") $(range_ports "$node1_range") $(range_ports "$node2_range"))}"
node1_aeron_port="${NODE1_AERON_PORT:-$(choose_free_port "$postgres_port" "$frontend_port" "$featureplant_port" "$wsclient_port" "$streamtap_port" "$node0_aeron_port" $(range_ports "$node0_range") $(range_ports "$node1_range") $(range_ports "$node2_range"))}"
node2_aeron_port="${NODE2_AERON_PORT:-$(choose_free_port "$postgres_port" "$frontend_port" "$featureplant_port" "$wsclient_port" "$streamtap_port" "$node0_aeron_port" "$node1_aeron_port" $(range_ports "$node0_range") $(range_ports "$node1_range") $(range_ports "$node2_range"))}"

cluster_subnet="${CLUSTER_SUBNET:-$(choose_cluster_subnet)}"
cluster_ip_range="${CLUSTER_DYNAMIC_IP_RANGE:-$(derive_cluster_ip_range "$cluster_subnet")}"
eval "$(cluster_ips_from_subnet "$cluster_subnet")"

featureplant_cursor="${FEATUREPLANT_DB_CURSOR_NAME:-${project}_featureplant}"
local_db_name="${LOCAL_DB_NAME:-kalshi_live_product}"
local_db_user="${LOCAL_DB_USER:-kalshi}"
local_db_password="${LOCAL_DB_PASSWORD:-kalshi_live_product}"
local_db_url="jdbc:postgresql://timescaledb:5432/${local_db_name}"
auth_user="${FRONTEND_ADAPTER_BASIC_AUTH_USER:-operator}"
auth_password="${FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD:-live-product-rehearsal-${project}}"
release_sha="${KALSHI_RELEASE_SHA:-$(git rev-parse --short=12 HEAD 2>/dev/null || printf 'unknown')}"
app_image="${KALSHI_APP_IMAGE:-kalshi-project:local}"
dashboard_url="http://127.0.0.1:${frontend_port}/"
frontend_base_url="http://127.0.0.1:${frontend_port}"
wsclient_health_url="http://127.0.0.1:${wsclient_port}/health"
streamtap_health_url="http://127.0.0.1:${streamtap_port}/health"
featureplant_health_url="http://127.0.0.1:${featureplant_port}/health"
featureplant_metrics_url="http://127.0.0.1:${featureplant_port}/metrics"
frontend_health_url="${frontend_base_url}/health"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

evidence_dir="${LIVE_PRODUCT_REHEARSAL_EVIDENCE_DIR:-.demo-state}"
artifact_dir="${LIVE_PRODUCT_REHEARSAL_ARTIFACT_DIR:-${evidence_dir}/live-product-rehearsal-${project}}"
mkdir -p "$artifact_dir"
evidence_file="${LIVE_PRODUCT_REHEARSAL_EVIDENCE_FILE:-${evidence_dir}/live-product-rehearsal-${project}.json}"
raw_log_dir="$(mktemp -d "${TMPDIR:-/tmp}/live-product-rehearsal.XXXXXX")"
trap 'rm -rf "$raw_log_dir"' EXIT INT HUP TERM
smoke_stdout="${artifact_dir}/live-product-smoke.stdout.log"
smoke_stderr="${artifact_dir}/live-product-smoke.stderr.log"
preflight_stdout="${artifact_dir}/live-preflight.stdout.log"
preflight_stderr="${artifact_dir}/live-preflight.stderr.log"
raw_smoke_stdout="${raw_log_dir}/live-product-smoke.stdout.log"
raw_smoke_stderr="${raw_log_dir}/live-product-smoke.stderr.log"
raw_preflight_stdout="${raw_log_dir}/live-preflight.stdout.log"
raw_preflight_stderr="${raw_log_dir}/live-preflight.stderr.log"
browser_artifact_dir="${LIVE_PRODUCT_REHEARSAL_BROWSER_ARTIFACT_DIR:-${artifact_dir}/browser}"
mkdir -p "$browser_artifact_dir"
browser_evidence_file="${browser_artifact_dir}/browser-smoke.json"
browser_dom_file="${browser_artifact_dir}/dashboard.dom.html"
browser_screenshot_file="${browser_artifact_dir}/dashboard.png"
browser_log_file="${browser_artifact_dir}/browser.log"

chmod 700 "$artifact_dir" "$browser_artifact_dir"

printf 'Starting isolated live product rehearsal project=%s profile=%s subnet=%s frontend_port=%s wsclient_metrics_port=%s max_markets=%s\n' \
    "$project" "$compose_profile" "$cluster_subnet" "$frontend_port" "$wsclient_port" "$market_discovery_max"

common_env() {
    COMPOSE_PROJECT_NAME="$project" \
    COMPOSE_HOST_BIND_IP=127.0.0.1 \
    COMPOSE_PROFILE="$compose_profile" \
    CLUSTER_SUBNET="$cluster_subnet" \
    CLUSTER_DYNAMIC_IP_RANGE="$cluster_ip_range" \
    CLUSTER_ADDRESSES="$CLUSTER_ADDRESSES" \
    CLUSTER_NODE0_IP="$CLUSTER_NODE0_IP" \
    CLUSTER_NODE1_IP="$CLUSTER_NODE1_IP" \
    CLUSTER_NODE2_IP="$CLUSTER_NODE2_IP" \
    WSCLIENT_IP="$WSCLIENT_IP" \
    STREAM_TAP_IP="$STREAM_TAP_IP" \
    NODE0_CLUSTER_PORT_RANGE="$node0_range" \
    NODE1_CLUSTER_PORT_RANGE="$node1_range" \
    NODE2_CLUSTER_PORT_RANGE="$node2_range" \
    NODE0_AERON_PORT="$node0_aeron_port" \
    NODE1_AERON_PORT="$node1_aeron_port" \
    NODE2_AERON_PORT="$node2_aeron_port" \
    POSTGRES_HOST_PORT="$postgres_port" \
    DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT="$frontend_port" \
    FEATUREPLANT_METRICS_HOST_PORT="$featureplant_port" \
    WSCLIENT_METRICS_HOST_PORT="$wsclient_port" \
    STREAM_TAP_HOST_PORT="$streamtap_port" \
    FRONTEND_BASE_URL="$frontend_base_url" \
    FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}" \
    WSCLIENT_HEALTH_URL="$wsclient_health_url" \
    STREAM_TAP_HEALTH_URL="$streamtap_health_url" \
    FEATUREPLANT_HEALTH_URL="$featureplant_health_url" \
    FEATUREPLANT_METRICS_URL="$featureplant_metrics_url" \
    FRONTEND_HEALTH_URL="$frontend_health_url" \
    LOCAL_DB_NAME="$local_db_name" \
    LOCAL_DB_USER="$local_db_user" \
    LOCAL_DB_PASSWORD="$local_db_password" \
    DB_WRITER_ENABLED="${DB_WRITER_ENABLED:-true}" \
    DB_WRITER_DATABASE_URL="$local_db_url" \
    DB_WRITER_DATABASE_USER="$local_db_user" \
    DB_WRITER_DATABASE_PASSWORD="$local_db_password" \
    FEATUREPLANT_DB_URL="$local_db_url" \
    FEATUREPLANT_DB_USER="$local_db_user" \
    FEATUREPLANT_DB_PASSWORD="$local_db_password" \
    FRONTEND_ADAPTER_DB_URL="$local_db_url" \
    FRONTEND_ADAPTER_DB_USER="$local_db_user" \
    FRONTEND_ADAPTER_DB_PASSWORD="$local_db_password" \
    FRONTEND_ADAPTER_SOURCE="${FRONTEND_ADAPTER_SOURCE:-db}" \
    FRONTEND_ADAPTER_FEATURE_SOURCE="${FRONTEND_ADAPTER_FEATURE_SOURCE:-latest_market_state}" \
    FRONTEND_ADAPTER_OPERATOR_CONTROL_ENABLED="${FRONTEND_ADAPTER_OPERATOR_CONTROL_ENABLED:-true}" \
    FRONTEND_ADAPTER_BASIC_AUTH_USER="$auth_user" \
    FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD="$auth_password" \
    FEATUREPLANT_SOURCE="${FEATUREPLANT_SOURCE:-db}" \
    FEATUREPLANT_OUTPUT="${FEATUREPLANT_OUTPUT:-db}" \
    FEATUREPLANT_DB_CURSOR_NAME="$featureplant_cursor" \
    FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED="${FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED:-true}" \
    LIVE_PRODUCT_SMOKE_REQUIRE_LIVE_DATA=true \
    LIVE_PRODUCT_BROWSER_SMOKE_ENABLED="${LIVE_PRODUCT_BROWSER_SMOKE_ENABLED:-false}" \
    LIVE_PRODUCT_SMOKE_DB_URL="$local_db_url" \
    LIVE_PRODUCT_SMOKE_DB_USER="$local_db_user" \
    LIVE_PRODUCT_SMOKE_DB_PASSWORD="$local_db_password" \
    PRODUCT_DEMO_LIVE_PREFLIGHT_SMOKE="${PRODUCT_DEMO_LIVE_PREFLIGHT_SMOKE:-true}" \
    PRODUCT_DEMO_LIVE_PREFLIGHT_REQUIRED="${PRODUCT_DEMO_LIVE_PREFLIGHT_REQUIRED:-true}" \
    PRODUCT_DEMO_LIVE_PREFLIGHT_S3_SMOKE="${PRODUCT_DEMO_LIVE_PREFLIGHT_S3_SMOKE:-false}" \
    KALSHI_BASE_URL="${KALSHI_BASE_URL:-https://api.elections.kalshi.com}" \
    KALSHI_KEY_ID="$KALSHI_KEY_ID" \
    KALSHI_KEY_HOST_PATH="$KALSHI_KEY_HOST_PATH" \
    KALSHI_KEY_PATH="$KALSHI_KEY_PATH" \
    KALSHI_MARKET_TICKERS="${KALSHI_MARKET_TICKERS:-}" \
    KALSHI_MARKET_SERIES_TICKER="${KALSHI_MARKET_SERIES_TICKER:-}" \
    KALSHI_MARKET_SELECTION_MODE="$market_selection_mode" \
    KALSHI_MARKET_STATUS="${KALSHI_MARKET_STATUS:-open}" \
    KALSHI_MARKET_MVE_FILTER="${KALSHI_MARKET_MVE_FILTER:-}" \
    KALSHI_MARKET_DISCOVERY_LIMIT="$market_discovery_limit" \
    KALSHI_MARKET_DISCOVERY_MAX_MARKETS="$market_discovery_max" \
    KALSHI_WS_CHANNELS="${KALSHI_WS_CHANNELS:-orderbook_delta,trade,ticker,market_lifecycle_v2}" \
    BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_ENABLED="${BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_ENABLED:-false}" \
    BACKEND_METRICS_PORT="${BACKEND_METRICS_PORT:-8091}" \
    WSCLIENT_START_DELAY_SECONDS="${WSCLIENT_START_DELAY_SECONDS:-20}" \
    KALSHI_RELEASE_SHA="$release_sha" \
    KALSHI_APP_IMAGE="$app_image" \
    KALSHI_DEPLOY_PROFILE="$compose_profile" \
    EXPECTED_KALSHI_RELEASE_SHA="$release_sha" \
    EXPECTED_KALSHI_APP_IMAGE="$app_image" \
    EXPECTED_KALSHI_DEPLOY_PROFILE="$compose_profile" \
    FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE="$browser_evidence_file" \
    FRONTEND_BROWSER_SMOKE_OUTPUT_DIR="$browser_artifact_dir" \
    FRONTEND_BROWSER_SMOKE_DOM_FILE="$browser_dom_file" \
    FRONTEND_BROWSER_SMOKE_SCREENSHOT_FILE="$browser_screenshot_file" \
    FRONTEND_BROWSER_SMOKE_LOG_FILE="$browser_log_file" \
    "$@"
}

if ! common_env docker_compose --profile "$compose_profile" up -d --build; then
    printf 'live product rehearsal compose up failed; cleanup with: scripts/live-product-rehearsal-up.sh --down %s\n' "$project" >&2
    exit 1
fi

preflight_status="skipped"
preflight_exit_code=0
if truthy "${PRODUCT_DEMO_LIVE_PREFLIGHT_SMOKE:-true}"; then
    if ! wait_frontend_health; then
        printf 'frontend did not become healthy before live credential preflight: %s\n' "$frontend_health_url" >&2
    fi
    set +e
    common_env scripts/product-demo-live-preflight-smoke.sh > "$raw_preflight_stdout" 2> "$raw_preflight_stderr"
    preflight_exit_code="$?"
    set -e
    redact_log "$raw_preflight_stdout" "$preflight_stdout"
    redact_log "$raw_preflight_stderr" "$preflight_stderr"
    if [ "$preflight_exit_code" -eq 0 ]; then
        preflight_status="passed"
        print_redacted_log "$preflight_stdout" 80
    else
        preflight_status="failed"
        print_redacted_log "$preflight_stdout" 80 >&2 || true
        print_redacted_log "$preflight_stderr" 80 >&2 || true
    fi
fi

smoke_status="failed"
set +e
common_env scripts/live-product-smoke.sh > "$raw_smoke_stdout" 2> "$raw_smoke_stderr"
smoke_exit_code="$?"
set -e
redact_log "$raw_smoke_stdout" "$smoke_stdout"
redact_log "$raw_smoke_stderr" "$smoke_stderr"
if [ "$smoke_exit_code" -eq 0 ]; then
    smoke_status="passed"
    print_redacted_log "$smoke_stdout" 220
else
    print_redacted_log "$smoke_stdout" 160 >&2 || true
    print_redacted_log "$smoke_stderr" 160 >&2 || true
fi

chmod 600 "$smoke_stdout" "$smoke_stderr" "$preflight_stdout" "$preflight_stderr" 2>/dev/null || true

tmp_evidence="${evidence_file}.tmp.$$"
python3 - "$tmp_evidence" "$evidence_file" "$smoke_stdout" "$preflight_stdout" "$browser_evidence_file" \
    "$project" "$compose_profile" "$release_sha" "$app_image" "$dashboard_url" "$frontend_health_url" \
    "$wsclient_health_url" "$streamtap_health_url" "$featureplant_health_url" "$featureplant_metrics_url" \
    "$postgres_port" "$frontend_port" "$featureplant_port" "$wsclient_port" "$streamtap_port" \
    "$cluster_subnet" "$cluster_ip_range" "$featureplant_cursor" "$started_at" "$smoke_status" "$smoke_exit_code" \
    "$preflight_status" "$preflight_exit_code" "$market_selection_mode" "${KALSHI_MARKET_TICKERS:-}" \
    "${KALSHI_MARKET_SERIES_TICKER:-}" "$market_discovery_max" "$market_discovery_limit" \
    "${KALSHI_MARKET_STATUS:-open}" "${KALSHI_MARKET_MVE_FILTER:-}" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

(
    evidence_path,
    final_evidence_path,
    smoke_log,
    preflight_log,
    browser_evidence_path,
    project,
    compose_profile,
    release_sha,
    app_image,
    dashboard_url,
    frontend_health_url,
    wsclient_health_url,
    streamtap_health_url,
    featureplant_health_url,
    featureplant_metrics_url,
    postgres_port,
    frontend_port,
    featureplant_port,
    wsclient_port,
    streamtap_port,
    cluster_subnet,
    cluster_ip_range,
    featureplant_cursor,
    started_at,
    smoke_status,
    smoke_exit_code,
    preflight_status,
    preflight_exit_code,
    market_selection_mode,
    market_tickers,
    market_series_ticker,
    market_discovery_max,
    market_discovery_limit,
    market_status,
    market_mve_filter,
) = sys.argv[1:]

def sha256_file(path):
    if not Path(path).is_file():
        return None
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()

labels = []
live_data_observed = False
require_live_data = False
product_latency = {}
if Path(smoke_log).is_file():
    with open(smoke_log, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            stripped = line.strip()
            match = re.match(r"^PASS\s+(\S+)", stripped)
            if match and match.group(1) not in labels:
                labels.append(match.group(1))
            for key, value in re.findall(r"([A-Za-z0-9_]+)=([^ ]+)", stripped):
                if key == "live_data_observed":
                    live_data_observed = value.lower() == "true"
                elif key == "require_live_data":
                    require_live_data = value.lower() == "true"
                elif stripped.startswith("PASS product_latency "):
                    product_latency[key] = value

browser_smoke = {
    "status": "skipped",
    "evidence_path": browser_evidence_path if Path(browser_evidence_path).is_file() else None,
    "dom_path": None,
    "dom_sha256": None,
    "screenshot_path": None,
    "screenshot_sha256": None,
    "checks": {},
}
if Path(browser_evidence_path).is_file():
    with open(browser_evidence_path, "r", encoding="utf-8") as handle:
        browser_body = json.load(handle)
    browser_smoke.update({
        "status": browser_body.get("status", "unknown"),
        "mode": browser_body.get("mode"),
        "reason": browser_body.get("reason"),
        "browser": browser_body.get("browser"),
        "docker_image": browser_body.get("docker_image"),
        "dom_path": browser_body.get("dom_path"),
        "dom_sha256": browser_body.get("dom_sha256"),
        "screenshot_path": browser_body.get("screenshot_path"),
        "screenshot_sha256": browser_body.get("screenshot_sha256"),
        "checks": browser_body.get("checks") or {},
    })

tickers = [ticker.strip() for ticker in market_tickers.split(",") if ticker.strip()]
body = {
    "schema_version": 1,
    "evidence_type": "live_product_rehearsal",
    "compose_profile": compose_profile,
    "compose_project_name": project,
    "started_at": started_at,
    "finished_at": __import__("datetime").datetime.utcnow().replace(microsecond=0).isoformat() + "Z",
    "status": "passed" if smoke_status == "passed" and preflight_status != "failed" else "failed",
    "release": {
        "sha": release_sha,
        "image": app_image,
        "profile": compose_profile,
    },
    "dashboard_url": dashboard_url,
    "health_urls": {
        "frontend": frontend_health_url,
        "wsclient": wsclient_health_url,
        "streamtap": streamtap_health_url,
        "featureplant": featureplant_health_url,
    },
    "featureplant_metrics_url": featureplant_metrics_url,
    "featureplant_cursor_name": featureplant_cursor,
    "ports": {
        "postgres": int(postgres_port),
        "frontend": int(frontend_port),
        "featureplant_metrics": int(featureplant_port),
        "wsclient_metrics": int(wsclient_port),
        "streamtap": int(streamtap_port),
    },
    "cluster_subnet": cluster_subnet,
    "cluster_ip_range": cluster_ip_range,
    "market_selection": {
        "mode": market_selection_mode,
        "configured_ticker_count": len(tickers),
        "configured_ticker_preview": tickers[:5],
        "series_ticker": market_series_ticker or None,
        "status": market_status,
        "mve_filter_configured": bool(market_mve_filter),
        "discovery_limit": int(market_discovery_limit),
        "discovery_max_markets": int(market_discovery_max),
    },
    "kalshi_credentials": {
        "key_id_configured": True,
        "key_host_path_configured": True,
        "key_path": "<redacted>",
    },
    "smoke": {
        "status": smoke_status,
        "exit_code": int(smoke_exit_code),
        "pass_labels": labels,
        "stdout_sha256": sha256_file(smoke_log),
        "live_data_observed": live_data_observed,
        "require_live_data": require_live_data,
        "product_latency": product_latency,
    },
    "preflight": {
        "status": preflight_status,
        "exit_code": int(preflight_exit_code),
        "stdout_sha256": sha256_file(preflight_log),
    },
    "browser_smoke": browser_smoke,
    "artifacts": {
        "smoke_stdout_path": smoke_log,
        "browser_evidence_path": browser_evidence_path if Path(browser_evidence_path).is_file() else None,
    },
    "cleanup_command": f"scripts/live-product-rehearsal-up.sh --down {final_evidence_path}",
}

raw = json.dumps(body, sort_keys=True)
for forbidden in ("KALSHI_KEY_ID", "KALSHI_KEY_HOST_PATH", "PRIVATE KEY", "DB_WRITER_DATABASE_PASSWORD"):
    if forbidden in raw:
        raise SystemExit(f"evidence contains forbidden secret marker: {forbidden}")

Path(evidence_path).parent.mkdir(parents=True, exist_ok=True)
with open(evidence_path, "w", encoding="utf-8") as handle:
    json.dump(body, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY
chmod 600 "$tmp_evidence"
mv "$tmp_evidence" "$evidence_file"

if [ "$preflight_status" = "failed" ] || [ "$smoke_exit_code" -ne 0 ]; then
    printf 'live product rehearsal failed; evidence=%s cleanup with: scripts/live-product-rehearsal-up.sh --down %s\n' \
        "$evidence_file" "$project" >&2
    exit 1
fi

printf 'PASS live_product_rehearsal project=%s dashboard_url=%s evidence=%s live_data_required=true cleanup_command=%s\n' \
    "$project" "$dashboard_url" "$evidence_file" "scripts/live-product-rehearsal-up.sh --down $evidence_file"
printf 'Dashboard URL: %s\n' "$dashboard_url"
printf 'Frontend health URL: %s\n' "$frontend_health_url"
printf 'Wsclient health URL: %s\n' "$wsclient_health_url"
printf 'StreamTap health URL: %s\n' "$streamtap_health_url"
printf 'FeaturePlant health URL: %s\n' "$featureplant_health_url"
printf 'Compose project: %s\n' "$project"
printf 'Cleanup command: scripts/live-product-rehearsal-up.sh --down %s\n' "$evidence_file"
