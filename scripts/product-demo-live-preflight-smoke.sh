#!/usr/bin/env sh
set -eu

FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
PRODUCT_DEMO_LIVE_PREFLIGHT_SMOKE="${PRODUCT_DEMO_LIVE_PREFLIGHT_SMOKE:-false}"
PRODUCT_DEMO_LIVE_PREFLIGHT_REQUIRED="${PRODUCT_DEMO_LIVE_PREFLIGHT_REQUIRED:-false}"
PRODUCT_DEMO_LIVE_PREFLIGHT_S3_SMOKE="${PRODUCT_DEMO_LIVE_PREFLIGHT_S3_SMOKE:-false}"
FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT:-8090}}"
SMOKE_HTTP_ATTEMPTS="${SMOKE_HTTP_ATTEMPTS:-30}"
SMOKE_HTTP_RETRY_SLEEP_SECONDS="${SMOKE_HTTP_RETRY_SLEEP_SECONDS:-1}"

truthy() {
    case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|y|on) return 0 ;;
        *) return 1 ;;
    esac
}

skip_or_fail() {
    reason="$1"
    if truthy "$PRODUCT_DEMO_LIVE_PREFLIGHT_REQUIRED"; then
        printf 'FAIL product_demo_live_preflight_smoke reason=%s\n' "$reason"
        exit 1
    fi
    printf 'SKIP product_demo_live_preflight_smoke reason=%s\n' "$reason"
    exit 0
}

env_or_file() {
    name="$1"
    value="$(printenv "$name" 2>/dev/null || true)"
    if [ -n "$value" ]; then
        printf '%s' "$value"
        return 0
    fi
    file_value="$(printenv "${name}_FILE" 2>/dev/null || true)"
    if [ -n "$file_value" ] && [ -r "$file_value" ]; then
        sed -n '1p' "$file_value"
        return 0
    fi
    return 0
}

if ! truthy "$PRODUCT_DEMO_LIVE_PREFLIGHT_SMOKE"; then
    skip_or_fail "disabled"
fi

FRONTEND_USER_VALUE="$(env_or_file FRONTEND_ADAPTER_BASIC_AUTH_USER)"
FRONTEND_PASSWORD_VALUE="$(env_or_file FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD)"
KALSHI_KEY_ID_VALUE="$(env_or_file KALSHI_KEY_ID)"
KALSHI_KEY_PATH_VALUE="$(env_or_file KALSHI_KEY_PATH)"
KALSHI_PRIVATE_KEY_VALUE="$(env_or_file KALSHI_PRIVATE_KEY)"

missing=""
[ -n "$FRONTEND_USER_VALUE" ] || missing="${missing}FRONTEND_ADAPTER_BASIC_AUTH_USER,"
[ -n "$FRONTEND_PASSWORD_VALUE" ] || missing="${missing}FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD,"
[ -n "$KALSHI_KEY_ID_VALUE" ] || missing="${missing}KALSHI_KEY_ID,"
if [ -z "$KALSHI_KEY_PATH_VALUE" ] && [ -z "$KALSHI_PRIVATE_KEY_VALUE" ]; then
    missing="${missing}KALSHI_KEY_PATH_OR_PRIVATE_KEY,"
fi
if [ -n "$missing" ]; then
    skip_or_fail "missing_config missing=${missing%,}"
fi

payload_file="$(mktemp)"
status_file="$(mktemp)"
trap 'rm -f "$payload_file" "$status_file"' EXIT

printf '%s\n' '{"action":"live_credential_check","confirm_live":true}' > "$payload_file"

frontend_curl() {
    curl -fsS --noproxy "$FRONTEND_NO_PROXY" \
        --user "$FRONTEND_USER_VALUE:$FRONTEND_PASSWORD_VALUE" \
        "$@"
}

frontend_curl \
    -X POST \
    -H "Content-Type: application/json" \
    --data-binary "@$payload_file" \
    "$FRONTEND_BASE_URL/operator/demo-orchestrator/run" >/dev/null

attempt=0
while [ "$attempt" -lt "$SMOKE_HTTP_ATTEMPTS" ]; do
    attempt=$((attempt + 1))
    if frontend_curl "$FRONTEND_BASE_URL/operator/demo-orchestrator/run-status" > "$status_file" \
        && python3 - "$status_file" <<'PY'
import json
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
latest = body.get("latest_run") or {}
if latest.get("state") in {"completed", "failed"}:
    raise SystemExit(0)
raise SystemExit(1)
PY
    then
        break
    fi
    sleep "$SMOKE_HTTP_RETRY_SLEEP_SECONDS"
done

python3 - "$status_file" <<'PY'
import json
import os
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
latest = body.get("latest_run") or {}
summary = latest.get("summary") or {}
preflight = summary.get("live_credential_preflight") or {}
if latest.get("state") != "completed":
    raise SystemExit(f"FAIL product_demo_live_preflight_smoke state={latest.get('state')}")
if latest.get("action") != "live_credential_check":
    raise SystemExit("FAIL product_demo_live_preflight_smoke reason=unexpected_action")
if preflight.get("source") != "live" or preflight.get("auth_ok") is not True:
    raise SystemExit(f"FAIL product_demo_live_preflight_smoke reason={preflight.get('failure_category') or 'auth_not_ok'}")
raw = json.dumps(body, sort_keys=True)
blocked = [
    "raw_response",
    "-----BEGIN",
    "PRIVATE KEY",
]
for env_name in (
    "KALSHI_KEY_ID",
    "KALSHI_KEY_PATH",
    "KALSHI_PRIVATE_KEY",
    "FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD",
    "DB_WRITER_DATABASE_PASSWORD",
    "FRONTEND_ADAPTER_DB_PASSWORD",
):
    value = os.environ.get(env_name, "")
    if len(value) >= 8:
        blocked.append(value)
for needle in blocked:
    if needle in raw:
        raise SystemExit(f"FAIL product_demo_live_preflight_smoke reason=secret_leaked needle={needle}")
print(
    "PASS product_demo_live_preflight_smoke "
    f"http_status={preflight.get('http_status')} market_count={preflight.get('market_count', 0)}"
)
PY

if truthy "$PRODUCT_DEMO_LIVE_PREFLIGHT_S3_SMOKE"; then
    s3_payload_file="$(mktemp)"
    s3_status_file="$(mktemp)"
    trap 'rm -f "$payload_file" "$status_file" "$s3_payload_file" "$s3_status_file"' EXIT
    printf '%s\n' '{"action":"s3_preflight_check","confirm_live":true}' > "$s3_payload_file"
    frontend_curl \
        -X POST \
        -H "Content-Type: application/json" \
        --data-binary "@$s3_payload_file" \
        "$FRONTEND_BASE_URL/operator/demo-orchestrator/run" >/dev/null
    frontend_curl "$FRONTEND_BASE_URL/operator/demo-orchestrator/run-status" > "$s3_status_file"
    python3 - "$s3_status_file" <<'PY'
import json
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
latest = body.get("latest_run") or {}
preflight = (latest.get("summary") or {}).get("s3_preflight") or {}
if latest.get("action") != "s3_preflight_check":
    raise SystemExit("FAIL product_demo_s3_preflight_smoke reason=unexpected_action")
if preflight.get("verified") is not False:
    raise SystemExit("FAIL product_demo_s3_preflight_smoke reason=unexpected_verified")
print(f"PASS product_demo_s3_preflight_smoke status={preflight.get('status')}")
PY
fi
