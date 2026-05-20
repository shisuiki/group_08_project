#!/usr/bin/env sh
set -eu

FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
CATALOG_SYNC_SMOKE_ENABLED="${CATALOG_SYNC_SMOKE_ENABLED:-false}"
CATALOG_SYNC_SMOKE_REQUIRED="${CATALOG_SYNC_SMOKE_REQUIRED:-false}"
CATALOG_SYNC_FRONTEND_BASE_URL="${CATALOG_SYNC_FRONTEND_BASE_URL:-http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT:-8090}}"
CATALOG_SYNC_LIMIT="${CATALOG_SYNC_LIMIT:-1}"
CATALOG_SYNC_MAX_PAGES="${CATALOG_SYNC_MAX_PAGES:-1}"
CATALOG_SYNC_MAX_TICKERS="${CATALOG_SYNC_MAX_TICKERS:-1}"
CATALOG_SYNC_DRY_RUN="${CATALOG_SYNC_DRY_RUN:-false}"
CATALOG_SYNC_MARKET_STATUS="${CATALOG_SYNC_MARKET_STATUS:-open}"
CATALOG_SYNC_SERIES_TICKER="${CATALOG_SYNC_SERIES_TICKER:-}"
CATALOG_SYNC_MVE_FILTER="${CATALOG_SYNC_MVE_FILTER:-}"

truthy() {
    case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|y|on) return 0 ;;
        *) return 1 ;;
    esac
}

skip_or_fail() {
    reason="$1"
    if truthy "$CATALOG_SYNC_SMOKE_REQUIRED"; then
        echo "FAIL catalog_sync_smoke reason=$reason"
        exit 1
    fi
    echo "SKIP catalog_sync_smoke reason=$reason"
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

if ! truthy "$CATALOG_SYNC_SMOKE_ENABLED"; then
    skip_or_fail "disabled"
fi

KALSHI_KEY_ID_VALUE="$(env_or_file KALSHI_KEY_ID)"
KALSHI_KEY_PATH_VALUE="$(env_or_file KALSHI_KEY_PATH)"
DB_URL_VALUE="$(env_or_file CATALOG_SYNC_DB_URL)"
if [ -z "$DB_URL_VALUE" ]; then
    DB_URL_VALUE="$(env_or_file FRONTEND_ADAPTER_DB_URL)"
fi
if [ -z "$DB_URL_VALUE" ]; then
    DB_URL_VALUE="$(env_or_file DB_WRITER_DATABASE_URL)"
fi
DB_USER_VALUE="$(env_or_file CATALOG_SYNC_DB_USER)"
if [ -z "$DB_USER_VALUE" ]; then
    DB_USER_VALUE="$(env_or_file FRONTEND_ADAPTER_DB_USER)"
fi
if [ -z "$DB_USER_VALUE" ]; then
    DB_USER_VALUE="$(env_or_file DB_WRITER_DATABASE_USER)"
fi
DB_PASSWORD_VALUE="$(env_or_file CATALOG_SYNC_DB_PASSWORD)"
if [ -z "$DB_PASSWORD_VALUE" ]; then
    DB_PASSWORD_VALUE="$(env_or_file FRONTEND_ADAPTER_DB_PASSWORD)"
fi
if [ -z "$DB_PASSWORD_VALUE" ]; then
    DB_PASSWORD_VALUE="$(env_or_file DB_WRITER_DATABASE_PASSWORD)"
fi
FRONTEND_USER_VALUE="$(env_or_file FRONTEND_ADAPTER_BASIC_AUTH_USER)"
FRONTEND_PASSWORD_VALUE="$(env_or_file FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD)"

missing=""
[ -n "$DB_URL_VALUE" ] || missing="${missing}DB_WRITER_DATABASE_URL,"
[ -n "$DB_USER_VALUE" ] || missing="${missing}DB_WRITER_DATABASE_USER,"
[ -n "$DB_PASSWORD_VALUE" ] || missing="${missing}DB_WRITER_DATABASE_PASSWORD,"
[ -n "$FRONTEND_USER_VALUE" ] || missing="${missing}FRONTEND_ADAPTER_BASIC_AUTH_USER,"
[ -n "$FRONTEND_PASSWORD_VALUE" ] || missing="${missing}FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD,"
if [ -n "$missing" ]; then
    missing="${missing%,}"
    skip_or_fail "missing_config missing=$missing"
fi

payload_file="$(mktemp)"
status_file="$(mktemp)"
trap 'rm -f "$payload_file" "$status_file"' EXIT

python3 - <<'PY' > "$payload_file"
import json
import os

payload = {
    "dry_run": os.environ.get("CATALOG_SYNC_DRY_RUN", "false").lower() in {"1", "true", "yes", "y", "on"},
    "market_status": os.environ.get("CATALOG_SYNC_MARKET_STATUS", "open"),
    "limit": int(os.environ.get("CATALOG_SYNC_LIMIT", "1")),
    "max_pages": int(os.environ.get("CATALOG_SYNC_MAX_PAGES", "1")),
    "max_tickers": int(os.environ.get("CATALOG_SYNC_MAX_TICKERS", "1")),
}
series = os.environ.get("CATALOG_SYNC_SERIES_TICKER", "").strip()
if series:
    payload["series_ticker"] = series
mve_filter = os.environ.get("CATALOG_SYNC_MVE_FILTER", "").strip()
if mve_filter:
    payload["mve_filter"] = mve_filter
print(json.dumps(payload, separators=(",", ":")))
PY

frontend_curl() {
    curl -fsS --noproxy "$FRONTEND_NO_PROXY" \
        --user "$FRONTEND_USER_VALUE:$FRONTEND_PASSWORD_VALUE" \
        "$@"
}

frontend_curl \
    -X POST \
    -H "Content-Type: application/json" \
    --data-binary "@$payload_file" \
    "$CATALOG_SYNC_FRONTEND_BASE_URL/operator/catalog/sync" >/dev/null

attempt=0
while [ "$attempt" -lt 60 ]; do
    attempt=$((attempt + 1))
    if frontend_curl "$CATALOG_SYNC_FRONTEND_BASE_URL/operator/catalog/sync-status" > "$status_file"; then
        state="$(python3 - "$status_file" <<'PY'
import json
import sys
body = json.load(open(sys.argv[1], encoding="utf-8"))
print((body.get("latest_run") or {}).get("state") or body.get("status") or "")
PY
)"
        if [ "$state" = "completed" ] || [ "$state" = "failed" ]; then
            break
        fi
    fi
    sleep 1
done

python3 - "$status_file" <<'PY'
import json
import os
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
latest = body.get("latest_run") or {}
summary = latest.get("summary") or {}
state = latest.get("state") or body.get("status")
raw = json.dumps(body)
secret_values = [
    os.environ.get("KALSHI_KEY_ID", ""),
    os.environ.get("KALSHI_KEY_PATH", ""),
    os.environ.get("FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD", ""),
    os.environ.get("DB_WRITER_DATABASE_PASSWORD", ""),
    os.environ.get("FRONTEND_ADAPTER_DB_PASSWORD", ""),
]
for value in secret_values:
    if len(value) < 8:
        continue
    if value and value in raw:
        raise SystemExit("FAIL catalog_sync_smoke reason=secret_leaked")
if state != "completed":
    raise SystemExit(f"FAIL catalog_sync_smoke state={state} failures={summary.get('failures', 0)}")
if int(summary.get("failures", 0)) != 0:
    raise SystemExit(f"FAIL catalog_sync_smoke failures={summary.get('failures')}")
markets_discovered = int(summary.get("markets_discovered", 0))
rows_upserted = int(summary.get("rows_upserted", 0))
dry_run_rows = int(summary.get("dry_run_rows", 0))
print(
    "PASS catalog_sync_smoke "
    f"status=completed markets_discovered={markets_discovered} "
    f"rows_upserted={rows_upserted} dry_run_rows={dry_run_rows}"
)
PY
