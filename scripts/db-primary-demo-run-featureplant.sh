#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

LOCAL_DB_NAME="${LOCAL_DB_NAME:-kalshi_test}"
LOCAL_DB_USER="${LOCAL_DB_USER:-kalshi}"
LOCAL_DB_PASSWORD="${LOCAL_DB_PASSWORD:-kalshi}"
FEATUREPLANT_DB_URL="${FEATUREPLANT_DB_URL:-jdbc:postgresql://timescaledb:5432/${LOCAL_DB_NAME}}"
FEATUREPLANT_DB_USER="${FEATUREPLANT_DB_USER:-$LOCAL_DB_USER}"
FEATUREPLANT_DB_PASSWORD="${FEATUREPLANT_DB_PASSWORD:-$LOCAL_DB_PASSWORD}"
FEATUREPLANT_DB_CURSOR_NAME="${FEATUREPLANT_DB_CURSOR_NAME:-}"
FEATUREPLANT_STREAMS="${FEATUREPLANT_STREAMS:-canonical.trade,canonical.ticker,derived.top_of_book}"
FEATUREPLANT_MODULES="${FEATUREPLANT_MODULES:-bbo,ticker_snapshot,trade_tape}"
FEATUREPLANT_MAX_EVENTS="${FEATUREPLANT_MAX_EVENTS:-10000}"
FEATUREPLANT_BATCH_SIZE="${FEATUREPLANT_BATCH_SIZE:-100}"
EXPECTED_FEATURE_OUTPUTS_BEFORE="${EXPECTED_FEATURE_OUTPUTS_BEFORE:-}"
EXPECTED_FEATURE_OUTPUTS_MIN="${EXPECTED_FEATURE_OUTPUTS_MIN:-1}"

cd "$REPO_ROOT"

count_outputs() {
    docker compose --profile local-db exec -T -e PGPASSWORD="$LOCAL_DB_PASSWORD" timescaledb \
        psql -qAt -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" -v ON_ERROR_STOP=1 \
        -c "select count(*) from feature_outputs where source_event_id like 'demo-db-primary-canonical-%'"
}

before_count="$(count_outputs)"
if [ -n "$EXPECTED_FEATURE_OUTPUTS_BEFORE" ] && [ "$before_count" != "$EXPECTED_FEATURE_OUTPUTS_BEFORE" ]; then
    printf 'featureplant precondition failed: feature_outputs_before=%s expected=%s\n' \
        "$before_count" "$EXPECTED_FEATURE_OUTPUTS_BEFORE" >&2
    exit 1
fi

FEATUREPLANT_SOURCE=db \
FEATUREPLANT_OUTPUT=db \
FEATUREPLANT_DB_URL="$FEATUREPLANT_DB_URL" \
FEATUREPLANT_DB_USER="$FEATUREPLANT_DB_USER" \
FEATUREPLANT_DB_PASSWORD="$FEATUREPLANT_DB_PASSWORD" \
FEATUREPLANT_DB_CURSOR_NAME="$FEATUREPLANT_DB_CURSOR_NAME" \
FEATUREPLANT_STREAMS="$FEATUREPLANT_STREAMS" \
FEATUREPLANT_MODULES="$FEATUREPLANT_MODULES" \
FEATUREPLANT_MAX_EVENTS="$FEATUREPLANT_MAX_EVENTS" \
FEATUREPLANT_BATCH_SIZE="$FEATUREPLANT_BATCH_SIZE" \
FEATUREPLANT_RUN_ONCE=true \
docker compose --profile featureplant run --rm --build featureplant

after_count="$(count_outputs)"
if [ "$after_count" -lt "$EXPECTED_FEATURE_OUTPUTS_MIN" ]; then
    printf 'featureplant output check failed: feature_outputs=%s expected_at_least=%s\n' \
        "$after_count" "$EXPECTED_FEATURE_OUTPUTS_MIN" >&2
    exit 1
fi

printf 'PASS demo_featureplant feature_outputs_before=%s feature_outputs_after=%s streams=%s modules=%s\n' \
    "$before_count" "$after_count" "$FEATUREPLANT_STREAMS" "$FEATUREPLANT_MODULES"
