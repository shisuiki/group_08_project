#!/usr/bin/env sh
set -eu
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

truthy() {
    case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|y|on) return 0 ;;
        *) return 1 ;;
    esac
}

DB_HOST="${SEMANTIC_METADATA_BACKFILL_DB_HOST:-${PGHOST:-127.0.0.1}}"
DB_PORT="${SEMANTIC_METADATA_BACKFILL_DB_PORT:-${PGPORT:-11000}}"
DB_NAME="${SEMANTIC_METADATA_BACKFILL_DB_NAME:-${PGDATABASE:-kalshi_live_product}}"
DB_USER="${SEMANTIC_METADATA_BACKFILL_DB_USER:-${PGUSER:-kalshi}}"
DB_PASSWORD="${SEMANTIC_METADATA_BACKFILL_DB_PASSWORD:-${PGPASSWORD:-kalshi_live_product}}"
DB_URL="${SEMANTIC_METADATA_BACKFILL_DB_URL:-${LLM_METADATA_DB_URL:-jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME}}"

JAR="${KALSHI_APP_JAR:-$REPO_ROOT/target/kalshi-project-1.0-SNAPSHOT.jar}"
BUILD_JAR="${SEMANTIC_METADATA_BACKFILL_BUILD_JAR:-true}"
CONCURRENCY="${SEMANTIC_METADATA_BACKFILL_CONCURRENCY:-8}"
MAX_CONCURRENCY="${SEMANTIC_METADATA_BACKFILL_MAX_CONCURRENCY:-32}"
MAX_MARKETS="${SEMANTIC_METADATA_BACKFILL_MAX_MARKETS:-500}"
MIN_BARS_24H="${SEMANTIC_METADATA_BACKFILL_MIN_BARS_24H:-10}"
MARKET_STATUS="${SEMANTIC_METADATA_BACKFILL_MARKET_STATUS:-active}"
TAXONOMY_VERSION="${LLM_METADATA_TAXONOMY_VERSION:-v1}"
MODEL="${LLM_METADATA_MODEL:-deepseek/deepseek-v4-flash:free}"
FALLBACK_MODEL="${LLM_METADATA_FALLBACK_MODEL:-deepseek/deepseek-v4-flash}"
MAX_RETRIES="${SEMANTIC_METADATA_BACKFILL_MAX_RETRIES:-1}"
MAX_TOKENS="${SEMANTIC_METADATA_BACKFILL_MAX_TOKENS:-2600}"
PER_MARKET_BUDGET_USD="${SEMANTIC_METADATA_BACKFILL_PER_MARKET_BUDGET_USD:-0.03}"
ESTIMATED_COST_USD="${SEMANTIC_METADATA_BACKFILL_ESTIMATED_COST_USD:-0.01}"
OVERWRITE="${SEMANTIC_METADATA_BACKFILL_OVERWRITE:-false}"
BACKGROUND="${SEMANTIC_METADATA_BACKFILL_BACKGROUND:-false}"
LOG_DIR="${SEMANTIC_METADATA_BACKFILL_LOG_DIR:-$REPO_ROOT/target/semantic-metadata-backfill/$(date +%Y%m%d%H%M%S)}"

validate_int_range() {
    name="$1"
    value="$2"
    min="$3"
    max="$4"
    case "$value" in
        ''|*[!0-9]*)
            printf '%s must be numeric, got %s\n' "$name" "$value" >&2
            exit 2
            ;;
    esac
    if [ "$value" -lt "$min" ] || [ "$value" -gt "$max" ]; then
        printf '%s must be between %s and %s, got %s\n' "$name" "$min" "$max" "$value" >&2
        exit 2
    fi
}

run_psql() {
    PGPASSWORD="$DB_PASSWORD" psql \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        -v ON_ERROR_STOP=1 \
        "$@"
}

print_status_snapshot() {
    label="$1"
    run_psql -v taxonomy="$TAXONOMY_VERSION" -v label="$label" -At <<'SQL'
select 'semantic_metadata_counts label=' || :'label' || ' status=' || status || ' count=' || count(*)
from market_semantic_metadata
where taxonomy_version = :'taxonomy'
group by status
order by status;
select 'semantic_metadata_models label=' || :'label' || ' model=' || coalesce(nullif(model, ''), '[blank]') || ' count=' || count(*)
from market_semantic_metadata
where taxonomy_version = :'taxonomy'
group by model
order by count(*) desc, model
limit 20;
select 'semantic_metadata_jobs label=' || :'label' || ' status=' || status || ' count=' || count(*)
from market_semantic_metadata_jobs
where taxonomy_version = :'taxonomy'
group by status
order by status;
SQL
}

print_recent_failures() {
    label="$1"
    run_psql -v taxonomy="$TAXONOMY_VERSION" -v label="$label" -At <<'SQL'
select 'semantic_metadata_failure label=' || :'label'
    || ' market=' || market_ticker
    || ' status=' || status
    || ' model=' || coalesce(nullif(model, ''), '[blank]')
    || ' error=' || left(regexp_replace(coalesce(error, ''), '[\r\n\t]+', ' ', 'g'), 180)
from market_semantic_metadata
where taxonomy_version = :'taxonomy'
  and status in ('failed', 'rate_limited', 'review_required')
order by updated_at desc nulls last, market_ticker
limit 20;
SQL
}

if truthy "$BACKGROUND"; then
    mkdir -p "$LOG_DIR"
    nohup env \
        OPENROUTER_API_KEY="${OPENROUTER_API_KEY:-}" \
        OPENROUTER_API_KEY_FILE="${OPENROUTER_API_KEY_FILE:-}" \
        SEMANTIC_METADATA_BACKFILL_BACKGROUND=false \
        SEMANTIC_METADATA_BACKFILL_LOG_DIR="$LOG_DIR" \
        SEMANTIC_METADATA_BACKFILL_DB_HOST="$DB_HOST" \
        SEMANTIC_METADATA_BACKFILL_DB_PORT="$DB_PORT" \
        SEMANTIC_METADATA_BACKFILL_DB_NAME="$DB_NAME" \
        SEMANTIC_METADATA_BACKFILL_DB_USER="$DB_USER" \
        SEMANTIC_METADATA_BACKFILL_DB_PASSWORD="$DB_PASSWORD" \
        SEMANTIC_METADATA_BACKFILL_DB_URL="$DB_URL" \
        KALSHI_APP_JAR="$JAR" \
        SEMANTIC_METADATA_BACKFILL_BUILD_JAR="$BUILD_JAR" \
        SEMANTIC_METADATA_BACKFILL_CONCURRENCY="$CONCURRENCY" \
        SEMANTIC_METADATA_BACKFILL_MAX_CONCURRENCY="$MAX_CONCURRENCY" \
        SEMANTIC_METADATA_BACKFILL_MAX_MARKETS="$MAX_MARKETS" \
        SEMANTIC_METADATA_BACKFILL_MIN_BARS_24H="$MIN_BARS_24H" \
        SEMANTIC_METADATA_BACKFILL_MARKET_STATUS="$MARKET_STATUS" \
        LLM_METADATA_TAXONOMY_VERSION="$TAXONOMY_VERSION" \
        LLM_METADATA_MODEL="$MODEL" \
        LLM_METADATA_FALLBACK_MODEL="$FALLBACK_MODEL" \
        SEMANTIC_METADATA_BACKFILL_MAX_RETRIES="$MAX_RETRIES" \
        SEMANTIC_METADATA_BACKFILL_MAX_TOKENS="$MAX_TOKENS" \
        SEMANTIC_METADATA_BACKFILL_PER_MARKET_BUDGET_USD="$PER_MARKET_BUDGET_USD" \
        SEMANTIC_METADATA_BACKFILL_ESTIMATED_COST_USD="$ESTIMATED_COST_USD" \
        SEMANTIC_METADATA_BACKFILL_OVERWRITE="$OVERWRITE" \
        sh "$0" "$@" > "$LOG_DIR/backfill.log" 2>&1 &
    pid="$!"
    printf 'STARTED semantic_metadata_backfill pid=%s log=%s\n' "$pid" "$LOG_DIR/backfill.log"
    exit 0
fi

if [ -z "${OPENROUTER_API_KEY:-}" ] && { [ -z "${OPENROUTER_API_KEY_FILE:-}" ] || [ ! -r "${OPENROUTER_API_KEY_FILE:-}" ]; }; then
    echo "OPENROUTER_API_KEY or readable OPENROUTER_API_KEY_FILE is required" >&2
    exit 2
fi

validate_int_range "SEMANTIC_METADATA_BACKFILL_MAX_CONCURRENCY" "$MAX_CONCURRENCY" 1 128
validate_int_range "SEMANTIC_METADATA_BACKFILL_CONCURRENCY" "$CONCURRENCY" 1 "$MAX_CONCURRENCY"
validate_int_range "SEMANTIC_METADATA_BACKFILL_MAX_MARKETS" "$MAX_MARKETS" 1 200000
validate_int_range "SEMANTIC_METADATA_BACKFILL_MIN_BARS_24H" "$MIN_BARS_24H" 0 1000000

if [ "$BUILD_JAR" = "true" ] || [ ! -f "$JAR" ]; then
    (cd "$REPO_ROOT" && ./mvnw -q -DskipTests package)
fi

mkdir -p "$LOG_DIR"
tickers_file="$LOG_DIR/tickers.txt"
results_file="$LOG_DIR/results.tsv"
: > "$results_file"
chmod 600 "$results_file"

print_status_snapshot "before" | tee "$LOG_DIR/status_before.log"

run_psql \
    -v taxonomy="$TAXONOMY_VERSION" \
    -v market_status="$MARKET_STATUS" \
    -v max_markets="$MAX_MARKETS" \
    -v min_bars_24h="$MIN_BARS_24H" \
    -At > "$tickers_file" <<'SQL'
select mm.market_ticker
from market_metadata mm
join market_feature_stats mfs
  on mfs.market_ticker = mm.market_ticker
 and mfs.display_eligible
left join market_semantic_metadata smm
  on smm.market_ticker = mm.market_ticker
 and smm.taxonomy_version = :'taxonomy'
 and smm.status = 'generated'
left join market_semantic_metadata_jobs job
  on job.market_ticker = mm.market_ticker
 and job.taxonomy_version = :'taxonomy'
 and job.status = 'running'
where smm.market_ticker is null
  and job.market_ticker is null
  and (:'market_status' = '' or mm.status = :'market_status')
  and coalesce(mfs.history_bars_24h_count, 0) >= :min_bars_24h
order by
  mfs.history_bars_24h_count desc nulls last,
  mfs.trade_24h_count desc nulls last,
  mfs.quote_24h_count desc nulls last,
  mm.series_ticker asc nulls last,
  mm.market_ticker asc
limit :max_markets
SQL

selected="$(wc -l < "$tickers_file" | tr -d ' ')"
printf 'semantic_metadata_backfill selected=%s concurrency=%s status=%s taxonomy=%s min_bars_24h=%s model=%s fallback=%s log_dir=%s\n' \
    "$selected" "$CONCURRENCY" "${MARKET_STATUS:-any}" "$TAXONOMY_VERSION" "$MIN_BARS_24H" "$MODEL" "$FALLBACK_MODEL" "$LOG_DIR"

if [ "$selected" = "0" ]; then
    printf 'semantic_metadata_backfill done selected=0\n'
    exit 0
fi

export JAR DB_URL DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD TAXONOMY_VERSION MODEL FALLBACK_MODEL
export MAX_RETRIES MAX_TOKENS PER_MARKET_BUDGET_USD ESTIMATED_COST_USD OVERWRITE LOG_DIR results_file

xargs -r -n 1 -P "$CONCURRENCY" sh -c '
    set -eu
    ticker="$1"
    safe="$(printf "%s" "$ticker" | tr -c "A-Za-z0-9_.-" "_")"
    stdout_file="$LOG_DIR/$safe.out"
    stderr_file="$LOG_DIR/$safe.err"
    sql_ticker="$(printf "%s" "$ticker" | sed "s/'"'"'/'"'"''"'"'/g")"
    sql_taxonomy="$(printf "%s" "$TAXONOMY_VERSION" | sed "s/'"'"'/'"'"''"'"'/g")"
    literal_ticker="$(printf "\047%s\047" "$sql_ticker")"
    literal_taxonomy="$(printf "\047%s\047" "$sql_taxonomy")"
    skip_query="select case
    when exists (
        select 1
        from market_semantic_metadata smm
        where smm.market_ticker = :ticker
          and smm.taxonomy_version = :taxonomy
          and smm.status = \$\$generated\$\$
    ) then \$\$generated\$\$
    when exists (
        select 1
        from market_semantic_metadata_jobs job
        where job.market_ticker = :ticker
          and job.taxonomy_version = :taxonomy
          and job.status = \$\$running\$\$
    ) then \$\$running\$\$
    else null
end"
    skip_reason="$(printf "%s\n" "$skip_query" | PGPASSWORD="$DB_PASSWORD" psql \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        -v ON_ERROR_STOP=1 \
        -v ticker="$literal_ticker" \
        -v taxonomy="$literal_taxonomy" \
        -At
)"
    if [ -n "$skip_reason" ]; then
        printf "%s\t0\tskipped_existing_or_running reason=%s\n" "$ticker" "$skip_reason" >> "$results_file"
        exit 0
    fi
    args="--market=$ticker --limit=1 --taxonomy-version=$TAXONOMY_VERSION --model=$MODEL --fallback-model=$FALLBACK_MODEL --max-retries=$MAX_RETRIES --max-tokens=$MAX_TOKENS --budget-usd=$PER_MARKET_BUDGET_USD --estimated-paid-request-cost-usd=$ESTIMATED_COST_USD"
    if [ "$OVERWRITE" = "true" ]; then
        args="$args --overwrite"
    fi
    set +e
    env \
        LLM_METADATA_DB_URL="$DB_URL" \
        LLM_METADATA_DB_USER="$DB_USER" \
        LLM_METADATA_DB_PASSWORD="$DB_PASSWORD" \
        java -cp "$JAR" edu.illinois.group8.semantic.SemanticMetadataCli $args \
        > "$stdout_file" 2> "$stderr_file"
    code="$?"
    set -e
    summary="$(grep "^semantic_metadata_summary " "$stdout_file" 2>/dev/null | tail -1 | sed "s/^semantic_metadata_summary //")"
    if [ -z "$summary" ]; then
        summary="$(tr "\n" " " < "$stderr_file" | sed "s/Bearer [^ ]*/Bearer [redacted]/g; s/OPENROUTER_API_KEY[^ ]*/OPENROUTER_API_KEY=[redacted]/g" | cut -c 1-240)"
    fi
    printf "%s\t%s\t%s\n" "$ticker" "$code" "$summary" >> "$results_file"
' sh < "$tickers_file"

ok_count="$(awk -F '\t' '$2 == 0 { c++ } END { print c + 0 }' "$results_file")"
nonzero_count="$(awk -F '\t' '$2 != 0 { c++ } END { print c + 0 }' "$results_file")"
soft_count="$(awk -F '\t' '$2 == 2 { c++ } END { print c + 0 }' "$results_file")"
skipped_count="$(awk -F '\t' '$3 ~ /^skipped_existing_or_running/ { c++ } END { print c + 0 }' "$results_file")"
printf 'semantic_metadata_backfill complete selected=%s ok=%s skipped_existing_or_running=%s soft_nonterminal=%s nonzero=%s results=%s\n' \
    "$selected" "$ok_count" "$skipped_count" "$soft_count" "$nonzero_count" "$results_file"
print_status_snapshot "after" | tee "$LOG_DIR/status_after.log"
print_recent_failures "after" | tee "$LOG_DIR/failures_after.log"
