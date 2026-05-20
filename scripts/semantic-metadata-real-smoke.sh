#!/usr/bin/env sh
set -eu

SEMANTIC_METADATA_REAL_SMOKE_ENABLED="${SEMANTIC_METADATA_REAL_SMOKE_ENABLED:-false}"
SEMANTIC_METADATA_REAL_SMOKE_REQUIRED="${SEMANTIC_METADATA_REAL_SMOKE_REQUIRED:-false}"
SEMANTIC_METADATA_REAL_SMOKE_STATUS="${SEMANTIC_METADATA_REAL_SMOKE_STATUS:-active}"
SEMANTIC_METADATA_REAL_SMOKE_MARKET_TICKER="${SEMANTIC_METADATA_REAL_SMOKE_MARKET_TICKER:-}"
SEMANTIC_METADATA_REAL_SMOKE_MAX_TOKENS="${SEMANTIC_METADATA_REAL_SMOKE_MAX_TOKENS:-2200}"
SEMANTIC_METADATA_REAL_SMOKE_MAX_RETRIES="${SEMANTIC_METADATA_REAL_SMOKE_MAX_RETRIES:-2}"
SEMANTIC_METADATA_REAL_SMOKE_BUDGET_USD="${SEMANTIC_METADATA_REAL_SMOKE_BUDGET_USD:-0.50}"
SEMANTIC_METADATA_REAL_SMOKE_ESTIMATED_COST_USD="${SEMANTIC_METADATA_REAL_SMOKE_ESTIMATED_COST_USD:-0.01}"
SEMANTIC_METADATA_REAL_SMOKE_OVERWRITE="${SEMANTIC_METADATA_REAL_SMOKE_OVERWRITE:-true}"
KALSHI_APP_JAR="${KALSHI_APP_JAR:-target/kalshi-project-1.0-SNAPSHOT.jar}"
KALSHI_APP_JAR_BUILD="${KALSHI_APP_JAR_BUILD:-true}"

truthy() {
    case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|y|on) return 0 ;;
        *) return 1 ;;
    esac
}

skip_or_fail() {
    reason="$1"
    if truthy "$SEMANTIC_METADATA_REAL_SMOKE_REQUIRED"; then
        echo "FAIL semantic_metadata_real_smoke reason=$reason"
        exit 1
    fi
    echo "SKIP semantic_metadata_real_smoke reason=$reason"
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

if ! truthy "$SEMANTIC_METADATA_REAL_SMOKE_ENABLED"; then
    skip_or_fail "disabled"
fi

if [ -z "${OPENROUTER_API_KEY_FILE:-}" ] || [ ! -r "$OPENROUTER_API_KEY_FILE" ]; then
    skip_or_fail "missing_openrouter_api_key_file"
fi

DB_URL_VALUE="$(env_or_file LLM_METADATA_DB_URL)"
if [ -z "$DB_URL_VALUE" ]; then
    DB_URL_VALUE="$(env_or_file DB_WRITER_DATABASE_URL)"
fi
DB_USER_VALUE="$(env_or_file LLM_METADATA_DB_USER)"
if [ -z "$DB_USER_VALUE" ]; then
    DB_USER_VALUE="$(env_or_file DB_WRITER_DATABASE_USER)"
fi
DB_PASSWORD_VALUE="$(env_or_file LLM_METADATA_DB_PASSWORD)"
if [ -z "$DB_PASSWORD_VALUE" ]; then
    DB_PASSWORD_VALUE="$(env_or_file DB_WRITER_DATABASE_PASSWORD)"
fi

missing=""
[ -n "$DB_URL_VALUE" ] || missing="${missing}DB_WRITER_DATABASE_URL,"
[ -n "$DB_USER_VALUE" ] || missing="${missing}DB_WRITER_DATABASE_USER,"
[ -n "$DB_PASSWORD_VALUE" ] || missing="${missing}DB_WRITER_DATABASE_PASSWORD,"
if [ -n "$missing" ]; then
    skip_or_fail "missing_config missing=${missing%,}"
fi

if truthy "$KALSHI_APP_JAR_BUILD" || [ ! -f "$KALSHI_APP_JAR" ]; then
    ./mvnw -q -DskipTests package
fi

stdout_file="$(mktemp)"
stderr_file="$(mktemp)"
trap 'rm -f "$stdout_file" "$stderr_file"' EXIT
chmod 600 "$stdout_file" "$stderr_file"

args="--limit=1 --status=$SEMANTIC_METADATA_REAL_SMOKE_STATUS --max-tokens=$SEMANTIC_METADATA_REAL_SMOKE_MAX_TOKENS --max-retries=$SEMANTIC_METADATA_REAL_SMOKE_MAX_RETRIES --budget-usd=$SEMANTIC_METADATA_REAL_SMOKE_BUDGET_USD --estimated-paid-request-cost-usd=$SEMANTIC_METADATA_REAL_SMOKE_ESTIMATED_COST_USD"
if truthy "$SEMANTIC_METADATA_REAL_SMOKE_OVERWRITE"; then
    args="$args --overwrite"
fi
if [ -n "$SEMANTIC_METADATA_REAL_SMOKE_MARKET_TICKER" ]; then
    args="$args --market=$SEMANTIC_METADATA_REAL_SMOKE_MARKET_TICKER"
fi

set +e
env -u OPENROUTER_API_KEY \
    OPENROUTER_API_KEY_FILE="$OPENROUTER_API_KEY_FILE" \
    LLM_METADATA_DB_URL="$DB_URL_VALUE" \
    LLM_METADATA_DB_USER="$DB_USER_VALUE" \
    LLM_METADATA_DB_PASSWORD="$DB_PASSWORD_VALUE" \
    LLM_METADATA_MAX_TOKENS="$SEMANTIC_METADATA_REAL_SMOKE_MAX_TOKENS" \
    java -cp "$KALSHI_APP_JAR" edu.illinois.group8.semantic.SemanticMetadataCli $args \
    > "$stdout_file" 2> "$stderr_file"
exit_code="$?"
set -e

if [ "$exit_code" != "0" ] && [ "$exit_code" != "2" ]; then
    sed 's/OPENROUTER_API_KEY[^ ]*/OPENROUTER_API_KEY=[redacted]/g' "$stderr_file"
    echo "FAIL semantic_metadata_real_smoke reason=cli_exit exit_code=$exit_code"
    exit 1
fi

python3 - "$stdout_file" "$stderr_file" "$OPENROUTER_API_KEY_FILE" <<'PY'
import pathlib
import re
import sys

stdout_path = pathlib.Path(sys.argv[1])
stderr_path = pathlib.Path(sys.argv[2])
key_path = pathlib.Path(sys.argv[3])
stdout = stdout_path.read_text(encoding="utf-8", errors="replace")
stderr = stderr_path.read_text(encoding="utf-8", errors="replace")
secret = key_path.read_text(encoding="utf-8", errors="replace").splitlines()[0].strip()
if len(secret) >= 8 and (secret in stdout or secret in stderr):
    raise SystemExit("FAIL semantic_metadata_real_smoke reason=secret_leaked")

summary_line = ""
for line in stdout.splitlines():
    if line.startswith("semantic_metadata_summary "):
        summary_line = line
        break
if not summary_line:
    raise SystemExit("FAIL semantic_metadata_real_smoke reason=missing_summary")

values = dict(re.findall(r"([a-z_]+)=([^ ]+)", summary_line))
processed = int(values.get("processed", "0"))
generated = int(values.get("generated", "0"))
review_required = int(values.get("review_required", "0"))
failed = int(values.get("failed", "0"))
rate_limited = int(values.get("rate_limited", "0"))
if processed != 1:
    raise SystemExit(f"FAIL semantic_metadata_real_smoke reason=unexpected_processed processed={processed}")
if failed != 0 or rate_limited != 0:
    raise SystemExit(
        "FAIL semantic_metadata_real_smoke "
        f"reason=bad_outcome failed={failed} rate_limited={rate_limited}"
    )
if generated + review_required < 1:
    raise SystemExit(
        "FAIL semantic_metadata_real_smoke "
        f"reason=no_generated_or_review_required generated={generated} review_required={review_required}"
    )
outcome = "generated" if generated else "review_required"
reason = "ok" if generated else "review_required_inspect_market_semantic_metadata_error_or_raw_response"
print(
    "PASS semantic_metadata_real_smoke "
    f"outcome={outcome} reason={reason} processed={processed} "
    f"generated={generated} review_required={review_required}"
)
PY
