#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

usage() {
    cat >&2 <<'EOF'
Usage:
  scripts/product-demo-up.sh
  scripts/product-demo-up.sh --down <evidence-json-or-compose-project>

Starts an isolated db-primary-product demo stack, runs the product smoke,
leaves the stack running, and writes non-secret evidence under .demo-state/.
EOF
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
    exec "$SCRIPT_DIR/product-demo-down.sh" "$2"
fi

if [ "$#" -ne 0 ]; then
    usage
    exit 2
fi

cd "$REPO_ROOT"

PROJECT_PREFIX="${PRODUCT_DEMO_PROJECT_PREFIX:-kalshi_product_demo}"
project="${PRODUCT_DEMO_PROJECT_NAME:-${PROJECT_PREFIX}_$(date -u +%Y%m%d%H%M%S)_$$}"
case "$project" in
    group_08_project|kalshi|default|"")
        printf 'refusing to use non-isolated compose project: %s\n' "$project" >&2
        exit 2
        ;;
esac

choose_free_port() {
    python3 - "$@" <<'PY'
import socket
import sys

used = {int(value) for value in sys.argv[1:] if value}
for _ in range(100):
    sock = socket.socket()
    try:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    finally:
        sock.close()
    if port not in used:
        print(port)
        raise SystemExit(0)
raise SystemExit("failed to allocate an unused localhost port")
PY
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

postgres_port="${POSTGRES_HOST_PORT:-$(choose_free_port)}"
frontend_port="${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT:-$(choose_free_port "$postgres_port")}"
featureplant_port="${FEATUREPLANT_METRICS_HOST_PORT:-$(choose_free_port "$postgres_port" "$frontend_port")}"
cluster_subnet="${CLUSTER_SUBNET:-$(choose_cluster_subnet)}"
dashboard_url="http://127.0.0.1:${frontend_port}/"
featureplant_metrics_url="http://127.0.0.1:${featureplant_port}/metrics"
featureplant_health_url="http://127.0.0.1:${featureplant_port}/health"
featureplant_cursor="${FEATUREPLANT_DB_CURSOR_NAME:-${project}_featureplant}"
evidence_dir="${PRODUCT_DEMO_EVIDENCE_DIR:-.demo-state}"
mkdir -p "$evidence_dir"
evidence_file="${PRODUCT_DEMO_EVIDENCE_FILE:-${evidence_dir}/product-demo-${project}.json}"
smoke_log="$(mktemp "${TMPDIR:-/tmp}/product-demo-smoke.XXXXXX")"
browser_log="$(mktemp "${TMPDIR:-/tmp}/product-demo-browser.XXXXXX")"
trap 'rm -f "$smoke_log" "$browser_log"' EXIT INT HUP TERM
browser_artifact_dir="${PRODUCT_DEMO_BROWSER_ARTIFACT_DIR:-${evidence_dir}/browser-${project}}"
mkdir -p "$browser_artifact_dir"
browser_evidence_file="${browser_artifact_dir}/browser-smoke.json"
browser_dom_file="${browser_artifact_dir}/dashboard.dom.html"
browser_screenshot_file="${browser_artifact_dir}/dashboard.png"

commit_sha="$(git rev-parse --short=12 HEAD 2>/dev/null || printf 'unknown')"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

printf 'Starting isolated product demo project=%s subnet=%s postgres_port=%s frontend_port=%s featureplant_metrics_port=%s\n' \
    "$project" "$cluster_subnet" "$postgres_port" "$frontend_port" "$featureplant_port"

if COMPOSE_PROJECT_NAME="$project" \
    COMPOSE_HOST_BIND_IP=127.0.0.1 \
    CLUSTER_SUBNET="$cluster_subnet" \
    POSTGRES_HOST_PORT="$postgres_port" \
    DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT="$frontend_port" \
    FEATUREPLANT_METRICS_HOST_PORT="$featureplant_port" \
    FEATUREPLANT_DB_CURSOR_NAME="$featureplant_cursor" \
    FRONTEND_BASE_URL="http://127.0.0.1:${frontend_port}" \
    FEATUREPLANT_METRICS_BASE_URL="http://127.0.0.1:${featureplant_port}" \
    scripts/db-primary-product-smoke.sh > "$smoke_log" 2>&1; then
    cat "$smoke_log"
else
    sed -n '1,160p' "$smoke_log" >&2 || true
    printf 'product demo smoke failed; cleanup with: scripts/product-demo-down.sh %s\n' "$project" >&2
    exit 1
fi

browser_status="skipped"
browser_reason="missing_browser"
browser_exit_code=0
browser_failed=0
if FRONTEND_BASE_URL="http://127.0.0.1:${frontend_port}" \
    BROWSER_BIN="${BROWSER_BIN:-}" \
    FRONTEND_BROWSER_SMOKE_DOCKER_ENABLED="${FRONTEND_BROWSER_SMOKE_DOCKER_ENABLED:-false}" \
    FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE="${FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE:-mcr.microsoft.com/playwright:v1.49.1-jammy}" \
    FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE="$browser_evidence_file" \
    FRONTEND_BROWSER_SMOKE_OUTPUT_DIR="$browser_artifact_dir" \
    FRONTEND_BROWSER_SMOKE_DOM_FILE="$browser_dom_file" \
    FRONTEND_BROWSER_SMOKE_SCREENSHOT_FILE="$browser_screenshot_file" \
    scripts/frontend-product-browser-smoke.sh > "$browser_log" 2>&1; then
    browser_status="passed"
    browser_reason=""
    browser_exit_code=0
    cat "$browser_log"
else
    browser_exit="$?"
    browser_exit_code="$browser_exit"
    if [ "$browser_exit" -eq 2 ]; then
        printf 'SKIP browser_smoke reason=missing_browser\n'
    else
        browser_status="failed"
        browser_reason="browser_smoke_failed"
        browser_failed=1
        sed -n '1,120p' "$browser_log" >&2 || true
    fi
fi

tmp_evidence="${evidence_file}.tmp.$$"
python3 - "$tmp_evidence" "$evidence_file" "$smoke_log" "$browser_evidence_file" "$project" "$postgres_port" "$frontend_port" "$featureplant_port" \
    "$cluster_subnet" "$featureplant_cursor" "$commit_sha" "$dashboard_url" "$featureplant_metrics_url" "$featureplant_health_url" \
    "$started_at" "$browser_status" "$browser_reason" "$browser_exit_code" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

(
    evidence_path,
    final_evidence_path,
    smoke_log,
    browser_evidence_path,
    project,
    postgres_port,
    frontend_port,
    featureplant_port,
    cluster_subnet,
    featureplant_cursor,
    commit_sha,
    dashboard_url,
    featureplant_metrics_url,
    featureplant_health_url,
    started_at,
    browser_status,
    browser_reason,
    browser_exit_code,
) = sys.argv[1:]

labels = []
with open(smoke_log, "r", encoding="utf-8", errors="replace") as handle:
    for line in handle:
        match = re.match(r"^PASS\s+(\S+)", line.strip())
        if match:
            label = match.group(1)
            if label not in labels:
                labels.append(label)
with open(smoke_log, "rb") as handle:
    smoke_sha256 = hashlib.sha256(handle.read()).hexdigest()

browser_smoke = {
    "status": browser_status,
    "mode": "none",
    "reason": browser_reason or None,
    "exit_code": int(browser_exit_code),
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
        "status": browser_body.get("status", browser_status),
        "mode": browser_body.get("mode", "none"),
        "reason": browser_body.get("reason"),
        "browser": browser_body.get("browser"),
        "docker_image": browser_body.get("docker_image"),
        "dom_path": browser_body.get("dom_path"),
        "dom_sha256": browser_body.get("dom_sha256"),
        "screenshot_path": browser_body.get("screenshot_path"),
        "screenshot_sha256": browser_body.get("screenshot_sha256"),
        "checks": browser_body.get("checks") or {},
    })

body = {
    "schema_version": 1,
    "evidence_type": "product_demo",
    "compose_profile": "db-primary-product",
    "started_at": started_at,
    "commit_sha": commit_sha,
    "compose_project_name": project,
    "featureplant_cursor_name": featureplant_cursor,
    "ports": {
        "postgres": int(postgres_port),
        "frontend": int(frontend_port),
        "featureplant_metrics": int(featureplant_port),
    },
    "cluster_subnet": cluster_subnet,
    "dashboard_url": dashboard_url,
    "featureplant_metrics_url": featureplant_metrics_url,
    "featureplant_health_url": featureplant_health_url,
    "smoke_pass_labels": labels,
    "smoke_stdout_sha256": smoke_sha256,
    "browser_smoke": browser_smoke,
    "cleanup_command": f"scripts/product-demo-down.sh {final_evidence_path}",
}
Path(evidence_path).parent.mkdir(parents=True, exist_ok=True)
with open(evidence_path, "w", encoding="utf-8") as handle:
    json.dump(body, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY
chmod 600 "$tmp_evidence"
mv "$tmp_evidence" "$evidence_file"

if [ "$browser_failed" -ne 0 ]; then
    printf 'product demo browser smoke failed; evidence=%s cleanup with: scripts/product-demo-down.sh %s\n' \
        "$evidence_file" "$project" >&2
    exit 1
fi

printf 'PASS product_demo project=%s dashboard_url=%s featureplant_metrics_url=%s evidence=%s cleanup_command=%s\n' \
    "$project" "$dashboard_url" "$featureplant_metrics_url" "$evidence_file" "scripts/product-demo-down.sh $evidence_file"
printf 'Dashboard URL: %s\n' "$dashboard_url"
printf 'FeaturePlant metrics URL: %s\n' "$featureplant_metrics_url"
printf 'Compose project: %s\n' "$project"
printf 'Cleanup command: scripts/product-demo-down.sh %s\n' "$evidence_file"
