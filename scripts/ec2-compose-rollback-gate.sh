#!/bin/sh
set -eu

DEPLOY_PROFILE="${DEPLOY_PROFILE:-cluster-live}"
CANDIDATE_ENV_FILE="${CANDIDATE_ENV_FILE:-.env.next}"
COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env}"
DEPLOY_STATE_DIR="${DEPLOY_STATE_DIR:-.deploy-state}"
WSCLIENT_METRICS_HOST_PORT="${WSCLIENT_METRICS_HOST_PORT:-8091}"
WSCLIENT_CAPTURE_METRICS_HOST_PORT="${WSCLIENT_CAPTURE_METRICS_HOST_PORT:-8093}"
FEATUREPLANT_METRICS_HOST_PORT="${FEATUREPLANT_METRICS_HOST_PORT:-8094}"
DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT="${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT:-8090}"
STREAM_TAP_HOST_PORT="${STREAM_TAP_HOST_PORT:-8080}"
STREAM_RECORDER_HOST_PORT="${STREAM_RECORDER_HOST_PORT:-8092}"
WSCLIENT_START_DELAY_SECONDS="${WSCLIENT_START_DELAY_SECONDS:-20}"
DEPLOY_DB_PREFLIGHT_REQUIRED="${DEPLOY_DB_PREFLIGHT_REQUIRED:-false}"
LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED="${LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED:-true}"
FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"
KALSHI_APP_IMAGE="${KALSHI_APP_IMAGE:-}"
CANDIDATE_IMAGE_TAR="${CANDIDATE_IMAGE_TAR:-}"
KALSHI_RELEASE_SHA="${KALSHI_RELEASE_SHA:-}"
KALSHI_GITHUB_RUN_ID="${KALSHI_GITHUB_RUN_ID:-}"
KALSHI_GITHUB_RUN_ATTEMPT="${KALSHI_GITHUB_RUN_ATTEMPT:-}"
RELEASE_EVIDENCE_DIR="$DEPLOY_STATE_DIR/releases"

COMPOSE_CONFIG_STATUS="not_run"
APP_IMAGE_STATUS="not_run"
DB_PREFLIGHT_STATUS="not_run"
COMPOSE_DOWN_STATUS="not_run"
COMPOSE_UP_STATUS="not_run"
RUNTIME_IMAGE_STATUS="not_run"
PROFILE_HEALTH_SMOKE_STATUS="not_run"
LIVE_PRODUCT_SEMANTIC_SMOKE_STATUS="not_applicable"
LIVE_PRODUCT_SMOKE_JSON='{"checked":false,"status":"not_applicable"}'
RECORD_SUCCESS_STATUS="not_run"
POST_GATE_FAILURE_CLASS="candidate_failed"
FRONTEND_RELEASE_HEALTH_JSON='{"checked":false,"status":"not_applicable"}'
ROLLBACK_ATTEMPTED="false"
ROLLBACK_STATUS="not_needed"
ROLLBACK_REASON=""
ROLLBACK_TARGET_REF=""
ROLLBACK_TARGET_PROFILE=""
ROLLBACK_TARGET_IMAGE=""
ROLLBACK_TARGET_IMAGE_TAR=""
ROLLBACK_TARGET_IMAGE_TAR_PRESENT="false"

down_all_profiles() {
    sudo docker compose --env-file "$1" \
        --profile cluster-live \
        --profile recording-capture \
        --profile observability \
        --profile local-db \
        --profile db-primary-product \
        --profile live-product \
        --profile featureplant \
        --profile raw-replay \
        --profile historical-backfill \
        down --remove-orphans
}

compose_profile() {
    env_file="$1"
    shift
    sudo docker compose --env-file "$env_file" --profile "$DEPLOY_PROFILE" "$@"
}

log() {
    printf '%s\n' "$*"
}

reset_gate_statuses() {
    COMPOSE_CONFIG_STATUS="not_run"
    APP_IMAGE_STATUS="not_run"
    DB_PREFLIGHT_STATUS="not_run"
    COMPOSE_DOWN_STATUS="not_run"
    COMPOSE_UP_STATUS="not_run"
    RUNTIME_IMAGE_STATUS="not_run"
    PROFILE_HEALTH_SMOKE_STATUS="not_run"
    LIVE_PRODUCT_SEMANTIC_SMOKE_STATUS="not_applicable"
    LIVE_PRODUCT_SMOKE_JSON='{"checked":false,"status":"not_applicable"}'
    FRONTEND_RELEASE_HEALTH_JSON='{"checked":false,"status":"not_applicable"}'
}

json_escape() {
    printf '%s' "$1" | awk '
        BEGIN { ORS = "" }
        {
            if (NR > 1) {
                printf "\\n"
            }
            gsub(/\\/, "\\\\")
            gsub(/"/, "\\\"")
            gsub(/\t/, "\\t")
            gsub(/\r/, "\\r")
            printf "%s", $0
        }
    '
}

json_string() {
    printf '"'
    json_escape "$1"
    printf '"'
}

json_string_or_null() {
    if [ -n "$1" ]; then
        json_string "$1"
    else
        printf 'null'
    fi
}

safe_evidence_component() {
    value="$1"
    if [ -z "$value" ]; then
        value="unknown"
    fi
    printf '%s' "$value" | tr -c 'A-Za-z0-9_.-' '_'
}

file_sha256() {
    file="$1"
    if [ -s "$file" ]; then
        sha256sum "$file" | awk '{ print $1 }'
    fi
}

diagnose_profile() {
    env_file="$1"
    log "Docker Compose status for DEPLOY_PROFILE=$DEPLOY_PROFILE:"
    sudo docker compose --env-file "$env_file" --profile "$DEPLOY_PROFILE" ps --all >&2 || true
    if [ "$DEPLOY_PROFILE" = "cluster-live" ]; then
        log "Recent wsclient/streamtap logs:"
        sudo docker compose --env-file "$env_file" --profile "$DEPLOY_PROFILE" logs --tail=120 wsclient streamtap >&2 || true
    elif [ "$DEPLOY_PROFILE" = "recording-capture" ]; then
        log "Recent wsclient-capture/stream-recorder logs:"
        sudo docker compose --env-file "$env_file" --profile "$DEPLOY_PROFILE" logs --tail=120 wsclient-capture stream-recorder >&2 || true
    elif [ "$DEPLOY_PROFILE" = "db-primary-product" ]; then
        log "Recent db-primary-product logs:"
        sudo docker compose --env-file "$env_file" --profile "$DEPLOY_PROFILE" logs --tail=120 \
            timescaledb db-migrate featureplant-db-follower frontend-adapter-db-primary >&2 || true
    elif [ "$DEPLOY_PROFILE" = "live-product" ]; then
        log "Recent live-product logs:"
        sudo docker compose --env-file "$env_file" --profile "$DEPLOY_PROFILE" logs --tail=120 \
            db-migrate-live node0 node1 node2 wsclient streamtap \
            featureplant-db-follower frontend-adapter-db-primary >&2 || true
    fi
}

numeric_or_default() {
    value="$1"
    fallback="$2"
    case "$value" in
        ''|*[!0-9]*) printf '%s\n' "$fallback" ;;
        *) printf '%s\n' "$value" ;;
    esac
}

env_file_value() {
    env_file="$1"
    key="$2"
    if [ ! -f "$env_file" ]; then
        return 0
    fi
    sed -n "s/^${key}=//p" "$env_file" | tail -n 1
}

compose_app_image() {
    env_file="$1"
    app_image="$(env_file_value "$env_file" KALSHI_APP_IMAGE)"
    if [ -z "$app_image" ] && [ "$env_file" = "$CANDIDATE_ENV_FILE" ]; then
        app_image="$KALSHI_APP_IMAGE"
    fi
    printf '%s\n' "$app_image"
}

compose_app_image_tar() {
    env_file="$1"
    image_tar="$(env_file_value "$env_file" KALSHI_APP_IMAGE_TAR)"
    if [ -z "$image_tar" ] && [ "$env_file" = "$CANDIDATE_ENV_FILE" ]; then
        image_tar="$CANDIDATE_IMAGE_TAR"
    fi
    printf '%s\n' "$image_tar"
}

evidence_env_file() {
    if [ -f "$CANDIDATE_ENV_FILE" ]; then
        printf '%s\n' "$CANDIDATE_ENV_FILE"
    else
        printf '%s\n' "$COMPOSE_ENV_FILE"
    fi
}

release_identity_value() {
    env_file="$1"
    key="$2"
    fallback="$3"
    value="$(env_file_value "$env_file" "$key")"
    if [ -z "$value" ]; then
        value="$fallback"
    fi
    printf '%s\n' "$value"
}

release_evidence_file() {
    kind="$1"
    env_file="$(evidence_env_file)"
    release_sha="$(release_identity_value "$env_file" KALSHI_RELEASE_SHA "${KALSHI_RELEASE_SHA:-$candidate_ref}")"
    run_id="$(release_identity_value "$env_file" KALSHI_GITHUB_RUN_ID "${KALSHI_GITHUB_RUN_ID:-local}")"
    run_attempt="$(release_identity_value "$env_file" KALSHI_GITHUB_RUN_ATTEMPT "${KALSHI_GITHUB_RUN_ATTEMPT:-1}")"
    safe_sha="$(safe_evidence_component "$release_sha")"
    safe_run_id="$(safe_evidence_component "$run_id")"
    safe_run_attempt="$(safe_evidence_component "$run_attempt")"
    case "$kind" in
        rollback) suffix="-rollback" ;;
        *) suffix="" ;;
    esac
    printf '%s/%s-%s-%s%s.json\n' "$RELEASE_EVIDENCE_DIR" "$safe_sha" "$safe_run_id" "$safe_run_attempt" "$suffix"
}

candidate_image_tar_json() {
    env_file="$1"
    image_tar="$(compose_app_image_tar "$env_file")"
    image_tar_sha=""
    image_tar_present="false"
    if [ -n "$image_tar" ] && [ -s "$image_tar" ]; then
        image_tar_present="true"
        image_tar_sha="$(file_sha256 "$image_tar")"
    fi
    printf '{"path":'
    json_string_or_null "$image_tar"
    printf ',"present":%s,"sha256":' "$image_tar_present"
    json_string_or_null "$image_tar_sha"
    printf '}'
}

runtime_images_json() {
    env_file="$1"
    separator=""
    printf '['
    for service in $(profile_app_services); do
        container_ids="$(compose_profile "$env_file" ps -q "$service" 2>/dev/null || true)"
        for container in $container_ids; do
            config_image="$(sudo docker inspect -f '{{.Config.Image}}' "$container" 2>/dev/null || true)"
            image_id="$(sudo docker inspect -f '{{.Image}}' "$container" 2>/dev/null || true)"
            printf '%s{"service":' "$separator"
            json_string "$service"
            printf ',"container_id":'
            json_string_or_null "$container"
            printf ',"config_image":'
            json_string_or_null "$config_image"
            printf ',"image_id":'
            json_string_or_null "$image_id"
            printf '}'
            separator=","
        done
    done
    printf ']'
}

frontend_health_url() {
    case "$DEPLOY_PROFILE" in
        db-primary-product|live-product)
            printf 'http://127.0.0.1:%s/health\n' "$DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT"
            ;;
        *)
            return 1
            ;;
    esac
}

frontend_release_health_json() {
    if ! url="$(frontend_health_url)"; then
        printf '{"checked":false,"status":"not_applicable"}'
        return 0
    fi
    mkdir -p "$DEPLOY_STATE_DIR"
    tmp_health="$DEPLOY_STATE_DIR/.frontend-health.$$"
    rm -f "$tmp_health"
    if ! curl -fsS --noproxy "$FRONTEND_NO_PROXY" --connect-timeout 1 --max-time 3 "$url" > "$tmp_health" 2>/dev/null; then
        rm -f "$tmp_health"
        printf '{"checked":true,"status":"failed","url":'
        json_string "$url"
        printf '}'
        return 0
    fi
    body_sha="$(file_sha256 "$tmp_health")"
    if command -v python3 >/dev/null 2>&1; then
        if python3 - "$tmp_health" "$url" "$body_sha" <<'PY'
import json
import sys

path, url, body_sha = sys.argv[1:4]
try:
    with open(path, "r", encoding="utf-8") as fh:
        body = json.load(fh)
except Exception:
    print(json.dumps({
        "checked": True,
        "status": "malformed",
        "url": url,
        "body_sha256": body_sha,
    }, separators=(",", ":")))
    raise SystemExit(0)

if not isinstance(body, dict):
    body = {}
release = body.get("release")
if not isinstance(release, dict):
    release = {}
freshness = body.get("data_freshness")
if not isinstance(freshness, dict):
    freshness = {}
refresh = body.get("feature_output_refresh")
if not isinstance(refresh, dict):
    refresh = {}
quote_updates = body.get("quote_updates")
if not isinstance(quote_updates, dict):
    quote_updates = {}
product_readiness = body.get("product_readiness")
if not isinstance(product_readiness, dict):
    product_readiness = {}

def pick(source, keys):
    return {key: source.get(key) for key in keys if key in source}

evidence = {
    "checked": True,
    "status": "observed",
    "url": url,
    "body_sha256": body_sha,
    "feature_source": body.get("feature_source"),
    "release": pick(release, ("sha", "image", "profile", "run_id", "run_attempt")),
    "data_freshness": {
        "latest_event_ts_ms": freshness.get("latest_event_ts_ms"),
        "latest_event_age_ms": freshness.get("latest_event_age_ms"),
        "store_sequence": freshness.get("store_sequence"),
        "symbol_present": bool(freshness.get("symbol")),
        "source_event_id_present": bool(freshness.get("source_event_id")),
    },
    "feature_output_refresh": pick(
        refresh,
        ("enabled", "running", "total_loaded", "refresh_errors", "last_success_at", "last_error_at"),
    ),
    "product_readiness": pick(
        product_readiness,
        ("status", "stale", "degraded", "stale_after_ms", "reasons"),
    ),
    "quote_updates": pick(
        quote_updates,
        ("requests", "changed", "timeouts", "rejected", "active_waits", "max_waits"),
    ),
}
print(json.dumps(evidence, separators=(",", ":")))
PY
        then
            rm -f "$tmp_health"
            return 0
        fi
    fi
    rm -f "$tmp_health"
    printf '{"checked":true,"status":"observed_unparsed","url":'
    json_string "$url"
    printf ',"body_sha256":'
    json_string_or_null "$body_sha"
    printf '}'
}

rollback_json() {
    printf '{"attempted":%s,"status":' "$ROLLBACK_ATTEMPTED"
    json_string "$ROLLBACK_STATUS"
    printf ',"reason":'
    json_string_or_null "$ROLLBACK_REASON"
    printf ',"target_ref":'
    json_string_or_null "$ROLLBACK_TARGET_REF"
    printf ',"target_profile":'
    json_string_or_null "$ROLLBACK_TARGET_PROFILE"
    printf ',"target_image":'
    json_string_or_null "$ROLLBACK_TARGET_IMAGE"
    printf ',"target_image_tar":'
    json_string_or_null "$ROLLBACK_TARGET_IMAGE_TAR"
    printf ',"target_image_tar_present":%s}' "$ROLLBACK_TARGET_IMAGE_TAR_PRESENT"
}

release_evidence_json() {
    kind="$1"
    outcome="$2"
    identity_env_file="$(evidence_env_file)"
    if [ "$kind" = "rollback" ]; then
        env_file="$COMPOSE_ENV_FILE"
    else
        env_file="$identity_env_file"
    fi
    release_sha="$(release_identity_value "$identity_env_file" KALSHI_RELEASE_SHA "${KALSHI_RELEASE_SHA:-$candidate_ref}")"
    run_id="$(release_identity_value "$identity_env_file" KALSHI_GITHUB_RUN_ID "${KALSHI_GITHUB_RUN_ID:-local}")"
    run_attempt="$(release_identity_value "$identity_env_file" KALSHI_GITHUB_RUN_ATTEMPT "${KALSHI_GITHUB_RUN_ATTEMPT:-1}")"
    release_profile="$(release_identity_value "$identity_env_file" KALSHI_DEPLOY_PROFILE "$DEPLOY_PROFILE")"
    app_image="$(compose_app_image "$env_file")"
    app_image_id=""
    if [ -n "$app_image" ]; then
        app_image_id="$(sudo docker image inspect -f '{{.Id}}' "$app_image" 2>/dev/null || true)"
    fi
    env_sha="$(file_sha256 "$env_file")"
    generated_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

    printf '{'
    printf '"schema_version":1'
    printf ',"evidence_type":'
    json_string "$kind"
    printf ',"generated_at":'
    json_string "$generated_at"
    printf ',"release_sha":'
    json_string_or_null "$release_sha"
    printf ',"github_run_id":'
    json_string_or_null "$run_id"
    printf ',"github_run_attempt":'
    json_string_or_null "$run_attempt"
    printf ',"deploy_profile":'
    json_string "$release_profile"
    printf ',"candidate_ref":'
    json_string_or_null "$candidate_ref"
    printf ',"app_image":'
    json_string_or_null "$app_image"
    printf ',"app_image_id":'
    json_string_or_null "$app_image_id"
    printf ',"candidate_image_tar":'
    candidate_image_tar_json "$env_file"
    printf ',"environment":{"env_file_sha256":'
    json_string_or_null "$env_sha"
    printf ',"redacted":true,"whitelisted":{"release_sha":'
    json_string_or_null "$release_sha"
    printf ',"deploy_profile":'
    json_string "$release_profile"
    printf ',"github_run_id":'
    json_string_or_null "$run_id"
    printf ',"github_run_attempt":'
    json_string_or_null "$run_attempt"
    printf ',"app_image":'
    json_string_or_null "$app_image"
    printf '}}'
    printf ',"gates":{'
    printf '"compose_config":'
    json_string "$COMPOSE_CONFIG_STATUS"
    printf ',"app_image":'
    json_string "$APP_IMAGE_STATUS"
    printf ',"db_preflight":'
    json_string "$DB_PREFLIGHT_STATUS"
    printf ',"compose_down":'
    json_string "$COMPOSE_DOWN_STATUS"
    printf ',"compose_up":'
    json_string "$COMPOSE_UP_STATUS"
    printf ',"runtime_images":'
    json_string "$RUNTIME_IMAGE_STATUS"
    printf ',"profile_health_smoke":'
    json_string "$PROFILE_HEALTH_SMOKE_STATUS"
    printf ',"live_product_semantic_smoke":'
    json_string "$LIVE_PRODUCT_SEMANTIC_SMOKE_STATUS"
    printf ',"record_last_success":'
    json_string "$RECORD_SUCCESS_STATUS"
    printf '}'
    printf ',"runtime_images":'
    runtime_images_json "$env_file"
    printf ',"frontend_release_health":%s' "$FRONTEND_RELEASE_HEALTH_JSON"
    printf ',"live_product_smoke":%s' "$LIVE_PRODUCT_SMOKE_JSON"
    printf ',"rollback":'
    rollback_json
    printf ',"outcome":'
    json_string "$outcome"
    printf '}'
}

write_release_evidence() {
    kind="$1"
    outcome="$2"
    mkdir -p "$RELEASE_EVIDENCE_DIR"
    chmod 700 "$DEPLOY_STATE_DIR" "$RELEASE_EVIDENCE_DIR"
    target="$(release_evidence_file "$kind")"
    tmp_file="$target.tmp.$$"
    rm -f "$tmp_file"
    if ! release_evidence_json "$kind" "$outcome" > "$tmp_file"; then
        rm -f "$tmp_file"
        log "Release evidence JSON generation failed for outcome=$outcome."
        return 1
    fi
    chmod 600 "$tmp_file"
    mv "$tmp_file" "$target"
    log "Wrote release evidence: $target outcome=$outcome."
}

load_app_image_from_tar() {
    app_image="$1"
    image_tar="$2"
    if [ -z "$image_tar" ]; then
        log "KALSHI_APP_IMAGE image not found locally and no image tar path is configured: $app_image"
        return 1
    fi
    if [ ! -s "$image_tar" ]; then
        log "KALSHI_APP_IMAGE image not found locally and image tar is missing or empty: $image_tar"
        return 1
    fi

    log "Reloading Docker image $app_image from retained image tar $image_tar."
    if ! gzip -dc "$image_tar" | sudo docker load >/dev/null; then
        log "Docker image load failed for retained image tar: $image_tar"
        return 1
    fi
    if ! sudo docker image inspect "$app_image" >/dev/null 2>&1; then
        log "Docker image tar did not provide expected KALSHI_APP_IMAGE: $app_image"
        return 1
    fi
}

build_or_verify_app_image() {
    env_file="$1"
    app_image="$(compose_app_image "$env_file")"
    if [ -n "$app_image" ]; then
        log "KALSHI_APP_IMAGE=$app_image is set; verifying image exists and skipping Docker Compose build."
        if ! sudo docker image inspect "$app_image" >/dev/null 2>&1; then
            image_tar="$(compose_app_image_tar "$env_file")"
            if ! load_app_image_from_tar "$app_image" "$image_tar"; then
                return 1
            fi
        fi
        return 0
    fi

    log "Building Docker Compose services for DEPLOY_PROFILE=$DEPLOY_PROFILE before stopping current services."
    if ! compose_profile "$env_file" build; then
        log "Docker Compose build failed."
        return 1
    fi
}

is_true() {
    case "$1" in
        true|TRUE|True|1|yes|YES|Yes) return 0 ;;
        *) return 1 ;;
    esac
}

db_preflight_service() {
    case "$DEPLOY_PROFILE" in
        cluster-live) printf '%s\n' wsclient ;;
        recording-capture) printf '%s\n' wsclient-capture ;;
        db-primary-product) printf '%s\n' featureplant-db-follower ;;
        live-product) printf '%s\n' wsclient ;;
        *) printf '%s\n' "" ;;
    esac
}

db_preflight_value() {
    env_file="$1"
    primary_key="$2"
    fallback_key="$3"
    value="$(env_file_value "$env_file" "$primary_key")"
    if [ -z "$value" ]; then
        value="$(env_file_value "$env_file" "$fallback_key")"
    fi
    printf '%s\n' "$value"
}

db_preflight_product_value() {
    env_file="$1"
    featureplant_key="$2"
    frontend_key="$3"
    writer_key="$4"
    value="$(env_file_value "$env_file" "$featureplant_key")"
    if [ -z "$value" ]; then
        value="$(env_file_value "$env_file" "$frontend_key")"
    fi
    if [ -z "$value" ]; then
        value="$(env_file_value "$env_file" "$writer_key")"
    fi
    printf '%s\n' "$value"
}

local_db_url() {
    env_file="$1"
    db_name="$(env_file_value "$env_file" LOCAL_DB_NAME)"
    if [ -z "$db_name" ]; then
        db_name="kalshi_test"
    fi
    printf 'jdbc:postgresql://timescaledb:5432/%s\n' "$db_name"
}

local_db_value() {
    env_file="$1"
    key="$2"
    fallback="$3"
    value="$(env_file_value "$env_file" "$key")"
    if [ -z "$value" ]; then
        value="$fallback"
    fi
    printf '%s\n' "$value"
}

effective_product_db_value() {
    env_file="$1"
    key="$2"
    fallback="$3"
    value="$(env_file_value "$env_file" "$key")"
    if [ -z "$value" ]; then
        value="$fallback"
    fi
    printf '%s\n' "$value"
}

validate_live_product_db_writer() {
    env_file="$1"
    db_enabled="$(env_file_value "$env_file" DB_WRITER_ENABLED)"
    db_url="$(env_file_value "$env_file" DB_WRITER_DATABASE_URL)"
    db_user="$(env_file_value "$env_file" DB_WRITER_DATABASE_USER)"
    db_password="$(env_file_value "$env_file" DB_WRITER_DATABASE_PASSWORD)"
    local_url="$(local_db_url "$env_file")"
    local_user="$(local_db_value "$env_file" LOCAL_DB_USER kalshi)"
    local_password="$(local_db_value "$env_file" LOCAL_DB_PASSWORD kalshi)"
    featureplant_url="$(effective_product_db_value "$env_file" FEATUREPLANT_DB_URL "${db_url:-$local_url}")"
    featureplant_user="$(effective_product_db_value "$env_file" FEATUREPLANT_DB_USER "${db_user:-$local_user}")"
    featureplant_password="$(effective_product_db_value "$env_file" FEATUREPLANT_DB_PASSWORD "${db_password:-$local_password}")"
    frontend_url="$(effective_product_db_value "$env_file" FRONTEND_ADAPTER_DB_URL "${db_url:-$local_url}")"
    frontend_user="$(effective_product_db_value "$env_file" FRONTEND_ADAPTER_DB_USER "${db_user:-$local_user}")"
    frontend_password="$(effective_product_db_value "$env_file" FRONTEND_ADAPTER_DB_PASSWORD "${db_password:-$local_password}")"

    case "$db_enabled" in
        true|TRUE|True) ;;
        *)
            log "live-product requires DB_WRITER_ENABLED=true."
            return 1
            ;;
    esac
    if [ -z "$db_url" ] || [ -z "$db_user" ] || [ -z "$db_password" ]; then
        log "live-product requires DB_WRITER_DATABASE_URL, DB_WRITER_DATABASE_USER, and DB_WRITER_DATABASE_PASSWORD."
        return 1
    fi
    if [ "$db_url" != "$featureplant_url" ] || [ "$db_url" != "$frontend_url" ]; then
        log "live-product requires DB writer, FeaturePlant, and frontend DB URLs to match."
        return 1
    fi
    if [ "$db_user" != "$featureplant_user" ] || [ "$db_user" != "$frontend_user" ]; then
        log "live-product requires DB writer, FeaturePlant, and frontend DB users to match."
        return 1
    fi
    if [ "$db_password" != "$featureplant_password" ] || [ "$db_password" != "$frontend_password" ]; then
        log "live-product requires DB writer, FeaturePlant, and frontend DB passwords to match."
        return 1
    fi
}

validate_live_product_frontend_feature_source() {
    env_file="$1"
    feature_source="$(env_file_value "$env_file" FRONTEND_ADAPTER_FEATURE_SOURCE)"
    if [ -z "$feature_source" ]; then
        feature_source="latest_market_state"
    fi
    case "$feature_source" in
        feature_outputs|latest_market_state|latest_state|latest-state) return 0 ;;
        *)
            log "live-product requires FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs or latest_market_state, got $feature_source."
            return 1
            ;;
    esac
}

run_db_release_preflight() {
    env_file="$1"
    service="$(db_preflight_service)"
    if [ -z "$service" ]; then
        DB_PREFLIGHT_STATUS="skipped"
        log "Skipping DB release preflight for DEPLOY_PROFILE=$DEPLOY_PROFILE."
        return 0
    fi

    required="$(env_file_value "$env_file" DEPLOY_DB_PREFLIGHT_REQUIRED)"
    if [ -z "$required" ]; then
        required="$DEPLOY_DB_PREFLIGHT_REQUIRED"
    fi
    case "$DEPLOY_PROFILE" in
        db-primary-product)
            db_url="$(db_preflight_product_value "$env_file" FEATUREPLANT_DB_URL FRONTEND_ADAPTER_DB_URL DB_WRITER_DATABASE_URL)"
            db_user="$(db_preflight_product_value "$env_file" FEATUREPLANT_DB_USER FRONTEND_ADAPTER_DB_USER DB_WRITER_DATABASE_USER)"
            db_password="$(db_preflight_product_value "$env_file" FEATUREPLANT_DB_PASSWORD FRONTEND_ADAPTER_DB_PASSWORD DB_WRITER_DATABASE_PASSWORD)"
            ;;
        live-product)
            if ! validate_live_product_db_writer "$env_file"; then
                DB_PREFLIGHT_STATUS="failed"
                return 1
            fi
            if ! validate_live_product_frontend_feature_source "$env_file"; then
                DB_PREFLIGHT_STATUS="failed"
                return 1
            fi
            db_url="$(env_file_value "$env_file" DB_WRITER_DATABASE_URL)"
            db_user="$(env_file_value "$env_file" DB_WRITER_DATABASE_USER)"
            db_password="$(env_file_value "$env_file" DB_WRITER_DATABASE_PASSWORD)"
            required="true"
            log "Running live-product Flyway migration against DB_WRITER_DATABASE_URL before release preflight."
            if ! compose_profile "$env_file" run --rm --no-deps -T db-migrate-live; then
                DB_PREFLIGHT_STATUS="failed"
                log "live-product Flyway migration failed."
                return 1
            fi
            ;;
        *)
            db_url="$(env_file_value "$env_file" DB_WRITER_DATABASE_URL)"
            db_user="$(env_file_value "$env_file" DB_WRITER_DATABASE_USER)"
            db_password="$(env_file_value "$env_file" DB_WRITER_DATABASE_PASSWORD)"
            ;;
    esac

    if [ -z "$db_url" ] && ! is_true "$required"; then
        DB_PREFLIGHT_STATUS="skipped"
        log "Skipping DB release preflight: candidate DB URL is empty and DEPLOY_DB_PREFLIGHT_REQUIRED=$required."
        return 0
    fi
    if [ -z "$db_url" ]; then
        DB_PREFLIGHT_STATUS="failed"
        log "DB release preflight required but candidate DB URL is empty."
        return 1
    fi

    DB_PREFLIGHT_STATUS="running"
    log "Running DB release preflight with candidate service $service before stopping current services."
    if ! compose_profile "$env_file" run --rm --no-deps -T \
        -e DEPLOY_DB_PREFLIGHT_REQUIRED="$required" \
        -e DB_PREFLIGHT_DATABASE_URL="$db_url" \
        -e DB_PREFLIGHT_DATABASE_USER="$db_user" \
        -e DB_PREFLIGHT_DATABASE_PASSWORD="$db_password" \
        "$service" java -cp /app/app.jar edu.illinois.group8.storage.db.DbReleasePreflightCli; then
        DB_PREFLIGHT_STATUS="failed"
        log "DB release preflight failed."
        return 1
    fi
    DB_PREFLIGHT_STATUS="passed"
    log "DB release preflight passed."
}

health_smoke_services() {
    if [ $(( $# % 2 )) -ne 0 ]; then
        log "Health smoke check requires name/url pairs."
        return 1
    fi
    start_delay="$(numeric_or_default "$WSCLIENT_START_DELAY_SECONDS" 20)"
    if [ "$start_delay" -gt 120 ]; then
        start_delay=120
    fi

    interval_seconds=5
    timeout_seconds=$((start_delay + 120))
    attempts=$(((timeout_seconds + interval_seconds - 1) / interval_seconds))
    attempt=1

    while [ "$attempt" -le "$attempts" ]; do
        all_ok=1
        pending=""
        index=1
        while [ "$index" -le "$#" ]; do
            eval "name=\${$index}"
            index=$((index + 1))
            eval "url=\${$index}"
            index=$((index + 1))
            if curl -fsS --noproxy "$FRONTEND_NO_PROXY" --connect-timeout 1 --max-time 2 "$url" >/dev/null 2>&1; then
                log "$name health check passed: $url"
            else
                all_ok=0
                pending="${pending}${pending:+,}$name"
            fi
        done
        if [ "$all_ok" -eq 1 ]; then
            return 0
        fi

        log "Health checks pending ($attempt/$attempts): $pending"
        if [ "$attempt" -lt "$attempts" ]; then
            sleep "$interval_seconds"
        fi
        attempt=$((attempt + 1))
    done

    log "Health smoke check failed after ${timeout_seconds}s."
    return 1
}

profile_health_smoke() {
    case "$DEPLOY_PROFILE" in
        cluster-live)
            health_smoke_services \
                wsclient "http://127.0.0.1:${WSCLIENT_METRICS_HOST_PORT}/health" \
                streamtap "http://127.0.0.1:${STREAM_TAP_HOST_PORT}/health"
            ;;
        recording-capture)
            health_smoke_services \
                wsclient-capture "http://127.0.0.1:${WSCLIENT_CAPTURE_METRICS_HOST_PORT}/health" \
                stream-recorder "http://127.0.0.1:${STREAM_RECORDER_HOST_PORT}/health"
            ;;
        db-primary-product)
            health_smoke_services \
                featureplant-db-follower "http://127.0.0.1:${FEATUREPLANT_METRICS_HOST_PORT}/health" \
                frontend-adapter-db-primary "http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT}/health"
            ;;
        live-product)
            health_smoke_services \
                wsclient "http://127.0.0.1:${WSCLIENT_METRICS_HOST_PORT}/health" \
                streamtap "http://127.0.0.1:${STREAM_TAP_HOST_PORT}/health" \
                featureplant-db-follower "http://127.0.0.1:${FEATUREPLANT_METRICS_HOST_PORT}/health" \
                frontend-adapter-db-primary "http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT}/health"
            ;;
        *)
            log "Skipping health smoke checks for DEPLOY_PROFILE=$DEPLOY_PROFILE."
            return 0
            ;;
    esac
}

profile_app_services() {
    case "$DEPLOY_PROFILE" in
        cluster-live) printf '%s\n' node0 node1 node2 wsclient streamtap ;;
        recording-capture) printf '%s\n' node0-capture wsclient-capture stream-recorder ;;
        db-primary-product) printf '%s\n' featureplant-db-follower frontend-adapter-db-primary ;;
        live-product) printf '%s\n' node0 node1 node2 wsclient streamtap featureplant-db-follower frontend-adapter-db-primary ;;
        featureplant) printf '%s\n' featureplant ;;
        raw-replay) printf '%s\n' raw-ingress-replay ;;
        historical-backfill) printf '%s\n' historical-backfill ;;
        *) return 0 ;;
    esac
}

assert_runtime_container_images() {
    env_file="$1"
    app_image="$(compose_app_image "$env_file")"
    if [ -z "$app_image" ]; then
        log "KALSHI_APP_IMAGE is unset; skipping runtime container image assertion."
        return 0
    fi

    expected_image="$app_image"
    for service in $(profile_app_services); do
        container_ids="$(compose_profile "$env_file" ps -q "$service" 2>/dev/null || true)"
        if [ -z "$container_ids" ]; then
            log "container image mismatch service=$service container=<none> expected=$expected_image actual=<missing>"
            return 1
        fi
        for container in $container_ids; do
            if ! actual_image="$(sudo docker inspect -f '{{.Config.Image}}' "$container")"; then
                log "container image mismatch service=$service container=$container expected=$expected_image actual=<inspect_failed>"
                return 1
            fi
            if [ "$actual_image" != "$expected_image" ]; then
                log "container image mismatch service=$service container=$container expected=$expected_image actual=$actual_image"
                return 1
            fi
        done
    done
    log "Runtime app containers use expected image $expected_image."
}

live_product_smoke_summary_json() {
    smoke_status="$1"
    stdout_file="$2"
    stderr_file="$3"
    stdout_sha="$(file_sha256 "$stdout_file")"
    stderr_sha="$(file_sha256 "$stderr_file")"
    python3 - "$smoke_status" "$stdout_file" "$stderr_file" "$stdout_sha" "$stderr_sha" <<'PY'
import json
import os
import re
import shlex
import sys

status, stdout_path, stderr_path, stdout_sha, stderr_sha = sys.argv[1:6]
ALLOWED_KEYS = {
    "pipeline_reliability": {
        "status",
        "window_seconds",
        "row_limit",
        "raw_recent",
        "raw_latest_receive_ts_ns",
        "canonical_recent",
        "canonical_max_commit_seq",
        "cursor_commit_seq",
        "cursor_lag_events",
        "feature_recent",
        "raw_without_canonical",
    },
    "health": {
        "service",
        "started_at",
        "feature_source",
        "expected_feature_source",
        "feature_output_refresh_total_loaded",
        "refresh_errors",
        "release_sha",
        "release_image",
        "release_profile",
        "freshness_event_ts_ms",
        "freshness_age_ms",
        "freshness_symbol",
        "freshness_source_event_id",
        "freshness_source_kind",
        "freshness_synthetic",
        "freshness_live_data_observed",
        "require_live_data",
        "product_readiness_status",
        "product_readiness_stale",
        "product_readiness_degraded",
    },
    "live_product_smoke": {
        "market",
        "run_id",
        "feature_source",
        "expected_feature_source",
        "cursor_before",
        "target_commit_seq",
        "cursor_after",
        "feature_outputs",
        "frontend_started_at",
        "frontend_total_loaded_before",
        "frontend_total_loaded_after",
        "frontend_refresh_errors_after",
        "freshness_source_kind",
        "freshness_synthetic",
        "freshness_live_data_observed",
        "live_data_observed",
        "require_live_data",
        "product_readiness_status",
        "product_readiness_stale",
        "product_readiness_degraded",
    },
    "product_latency": {
        "market",
        "run_id",
        "source_event_id",
        "source_kind",
        "synthetic",
        "canonical_commit_seq",
        "latest_market_state_commit_seq",
        "canonical_to_feature_ms",
        "feature_to_latest_state_ms",
        "canonical_to_latest_state_ms",
        "seed_to_cursor_ms",
        "seed_to_feature_ms",
        "seed_to_frontend_feature_ms",
        "seed_to_frontend_quote_ms",
        "seed_to_sse_ms",
        "seed_insert_ms",
        "max_allowed_ms",
        "status",
    },
}

def convert(value):
    lowered = value.lower()
    if lowered == "true":
        return True
    if lowered == "false":
        return False
    if re.fullmatch(r"-?[0-9]+", value):
        try:
            return int(value)
        except ValueError:
            return value
    return value

def parse_pass_lines(path):
    passes = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            lines = handle.readlines()
    except FileNotFoundError:
        return passes
    for raw_line in lines:
        line = raw_line.strip()
        if not line.startswith("PASS "):
            continue
        try:
            tokens = shlex.split(line)
        except ValueError:
            continue
        if len(tokens) < 2 or tokens[0] != "PASS":
            continue
        label = tokens[1]
        allowed = ALLOWED_KEYS.get(label)
        if allowed is None:
            continue
        fields = {}
        for token in tokens[2:]:
            if "=" not in token:
                continue
            key, value = token.split("=", 1)
            if key in allowed:
                fields[key] = convert(value)
        passes.setdefault(label, []).append(fields)
    return passes

def latest(records, predicate=lambda value: True):
    for record in reversed(records):
        if predicate(record):
            return record
    return None

passes = parse_pass_lines(stdout_path)
frontend_health = latest(
    passes.get("health", []),
    lambda item: item.get("service") == "frontend-adapter",
)
summary = {
    "checked": True,
    "status": status,
    "output_sha256": stdout_sha or None,
    "stderr_sha256": stderr_sha or None,
    "stderr_present": os.path.exists(stderr_path) and os.path.getsize(stderr_path) > 0,
    "pass_labels": sorted(passes),
    "pipeline_reliability": latest(passes.get("pipeline_reliability", [])),
    "frontend_health": frontend_health,
    "product_latency": latest(passes.get("product_latency", [])),
    "final_pass": latest(passes.get("live_product_smoke", [])) is not None,
    "live_product_smoke": latest(passes.get("live_product_smoke", [])),
}
print(json.dumps(summary, separators=(",", ":")))
PY
}

run_live_product_semantic_smoke() {
    env_file="$1"
    if [ "$DEPLOY_PROFILE" != "live-product" ]; then
        LIVE_PRODUCT_SEMANTIC_SMOKE_STATUS="not_applicable"
        LIVE_PRODUCT_SMOKE_JSON='{"checked":false,"status":"not_applicable"}'
        return 0
    fi

    if ! validate_live_product_frontend_feature_source "$env_file"; then
        LIVE_PRODUCT_SEMANTIC_SMOKE_STATUS="failed"
        LIVE_PRODUCT_SMOKE_JSON='{"checked":true,"status":"failed","reason":"frontend_feature_source"}'
        return 1
    fi

    enabled="$(env_file_value "$env_file" LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED)"
    if [ -z "$enabled" ]; then
        enabled="$LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED"
    fi
    if ! is_true "$enabled"; then
        LIVE_PRODUCT_SEMANTIC_SMOKE_STATUS="failed"
        LIVE_PRODUCT_SMOKE_JSON='{"checked":true,"status":"failed","reason":"disabled"}'
        log "live-product semantic smoke must be enabled before recording a live-product deploy success."
        return 1
    fi

    LIVE_PRODUCT_SEMANTIC_SMOKE_STATUS="running"
    log "Running live-product semantic smoke before recording candidate success."
    mkdir -p "$DEPLOY_STATE_DIR"
    chmod 700 "$DEPLOY_STATE_DIR"
    smoke_stdout="$DEPLOY_STATE_DIR/.live-product-smoke.stdout.$$"
    smoke_stderr="$DEPLOY_STATE_DIR/.live-product-smoke.stderr.$$"
    rm -f "$smoke_stdout" "$smoke_stderr"
    : > "$smoke_stdout"
    : > "$smoke_stderr"
    chmod 600 "$smoke_stdout" "$smoke_stderr"
    if COMPOSE_ENV_FILE="$env_file" \
        COMPOSE_PROFILE="$DEPLOY_PROFILE" \
        LIVE_PRODUCT_SMOKE_DOCKER_SUDO=true \
        FRONTEND_NO_PROXY="$FRONTEND_NO_PROXY" \
        sh scripts/live-product-smoke.sh > "$smoke_stdout" 2> "$smoke_stderr"; then
        cat "$smoke_stdout"
        LIVE_PRODUCT_SEMANTIC_SMOKE_STATUS="passed"
        LIVE_PRODUCT_SMOKE_JSON="$(live_product_smoke_summary_json "passed" "$smoke_stdout" "$smoke_stderr")"
        rm -f "$smoke_stdout" "$smoke_stderr"
        return 0
    else
        LIVE_PRODUCT_SEMANTIC_SMOKE_STATUS="failed"
        if [ -s "$smoke_stdout" ]; then
            cat "$smoke_stdout"
        fi
        if [ -s "$smoke_stderr" ]; then
            cat "$smoke_stderr" >&2
        fi
        LIVE_PRODUCT_SMOKE_JSON="$(live_product_smoke_summary_json "failed" "$smoke_stdout" "$smoke_stderr")"
        rm -f "$smoke_stdout" "$smoke_stderr"
        log "live-product semantic smoke failed."
        return 1
    fi
}

deploy_env() {
    env_file="$1"
    candidate="$2"

    reset_gate_statuses
    log "Validating Docker Compose config for DEPLOY_PROFILE=$DEPLOY_PROFILE."
    COMPOSE_CONFIG_STATUS="running"
    if ! compose_profile "$env_file" config --quiet; then
        COMPOSE_CONFIG_STATUS="failed"
        log "Docker Compose config validation failed."
        return 1
    fi
    COMPOSE_CONFIG_STATUS="passed"

    APP_IMAGE_STATUS="running"
    if ! build_or_verify_app_image "$env_file"; then
        APP_IMAGE_STATUS="failed"
        return 1
    fi
    APP_IMAGE_STATUS="passed"

    if ! run_db_release_preflight "$env_file"; then
        return 1
    fi

    if [ "$candidate" = "true" ]; then
        cp "$env_file" "$COMPOSE_ENV_FILE"
        chmod 600 "$COMPOSE_ENV_FILE"
        env_file="$COMPOSE_ENV_FILE"
        log "Promoted candidate environment to $COMPOSE_ENV_FILE."
    fi

    log "Stopping existing Compose services for controlled deploy."
    COMPOSE_DOWN_STATUS="running"
    if ! down_all_profiles "$env_file"; then
        COMPOSE_DOWN_STATUS="failed"
        log "Docker Compose down failed."
        return 1
    fi
    COMPOSE_DOWN_STATUS="passed"

    log "Starting DEPLOY_PROFILE=$DEPLOY_PROFILE with prebuilt image."
    COMPOSE_UP_STATUS="running"
    if ! compose_profile "$env_file" up -d --no-build --remove-orphans; then
        COMPOSE_UP_STATUS="failed"
        log "Docker Compose up failed."
        return 1
    fi
    COMPOSE_UP_STATUS="passed"

    RUNTIME_IMAGE_STATUS="running"
    if ! assert_runtime_container_images "$env_file"; then
        RUNTIME_IMAGE_STATUS="failed"
        diagnose_profile "$env_file"
        return 1
    fi
    RUNTIME_IMAGE_STATUS="passed"

    PROFILE_HEALTH_SMOKE_STATUS="running"
    if ! profile_health_smoke; then
        PROFILE_HEALTH_SMOKE_STATUS="failed"
        diagnose_profile "$env_file"
        return 1
    fi
    PROFILE_HEALTH_SMOKE_STATUS="passed"
    FRONTEND_RELEASE_HEALTH_JSON="$(frontend_release_health_json)"

    if ! run_live_product_semantic_smoke "$env_file"; then
        diagnose_profile "$env_file"
        return 1
    fi

    return 0
}

record_success() {
    mkdir -p "$DEPLOY_STATE_DIR"
    chmod 700 "$DEPLOY_STATE_DIR"
    success_ref="$(git rev-parse HEAD)"
    app_image="$(compose_app_image "$COMPOSE_ENV_FILE")"
    image_tar=""
    if [ -n "$app_image" ]; then
        image_tar="$(compose_app_image_tar "$COMPOSE_ENV_FILE")"
        if ! sudo docker image inspect "$app_image" >/dev/null 2>&1; then
            log "Cannot record success; KALSHI_APP_IMAGE is missing locally: $app_image"
            return 1
        fi
        if [ -z "$image_tar" ] || [ ! -s "$image_tar" ]; then
            log "Cannot record success; retained image tar is missing for KALSHI_APP_IMAGE=$app_image: ${image_tar:-<unset>}"
            return 1
        fi
    fi

    tmp_prefix="$DEPLOY_STATE_DIR/.last_success.$$"
    tmp_ref="$tmp_prefix.ref"
    tmp_env="$tmp_prefix.env"
    tmp_profile="$tmp_prefix.profile"
    tmp_image="$tmp_prefix.image"
    tmp_image_tar="$tmp_prefix.image_tar"
    rm -f "$tmp_ref" "$tmp_env" "$tmp_profile" "$tmp_image" "$tmp_image_tar"
    printf '%s\n' "$success_ref" > "$tmp_ref"
    cp "$COMPOSE_ENV_FILE" "$tmp_env"
    printf '%s\n' "$DEPLOY_PROFILE" > "$tmp_profile"
    chmod 600 "$tmp_ref" "$tmp_env" "$tmp_profile"
    if [ -n "$app_image" ]; then
        printf '%s\n' "$app_image" > "$tmp_image"
        printf '%s\n' "$image_tar" > "$tmp_image_tar"
        chmod 600 "$tmp_image" "$tmp_image_tar"
    else
        rm -f "$tmp_image" "$tmp_image_tar"
    fi

    mv "$tmp_ref" "$DEPLOY_STATE_DIR/last_success.ref"
    mv "$tmp_env" "$DEPLOY_STATE_DIR/last_success.env"
    mv "$tmp_profile" "$DEPLOY_STATE_DIR/last_success.profile"
    if [ -n "$app_image" ]; then
        mv "$tmp_image" "$DEPLOY_STATE_DIR/last_success.image"
        mv "$tmp_image_tar" "$DEPLOY_STATE_DIR/last_success.image_tar"
    else
        rm -f "$DEPLOY_STATE_DIR/last_success.image" "$DEPLOY_STATE_DIR/last_success.image_tar"
    fi
    rm -f "$CANDIDATE_ENV_FILE"
    log "Recorded last-success deploy state in $DEPLOY_STATE_DIR."
}

restore_last_success_checkout() {
    previous_ref="$1"
    if ! git cat-file -e "${previous_ref}^{commit}" >/dev/null 2>&1; then
        git fetch --prune origin
    fi
    git checkout --detach "$previous_ref"
    git reset --hard "$previous_ref"
}

load_last_success_image_if_needed() {
    previous_image_file="$DEPLOY_STATE_DIR/last_success.image"
    previous_image_tar_file="$DEPLOY_STATE_DIR/last_success.image_tar"

    if [ ! -s "$previous_image_file" ]; then
        log "No last-success image is recorded; rollback will use the local Compose build path."
        return 0
    fi

    previous_image="$(sed -n '1p' "$previous_image_file")"
    previous_image_tar="$(sed -n '1p' "$previous_image_tar_file" 2>/dev/null || true)"
    if [ -z "$previous_image" ]; then
        log "Last-success image is empty; cannot rollback."
        return 1
    fi
    if sudo docker image inspect "$previous_image" >/dev/null 2>&1; then
        return 0
    fi
    if [ -z "$previous_image_tar" ] || [ ! -s "$previous_image_tar" ]; then
        log "Last-success Docker image is missing locally and last_success.image_tar is unavailable for $previous_image."
        return 1
    fi

    if ! load_app_image_from_tar "$previous_image" "$previous_image_tar"; then
        return 1
    fi
}

rollback_to_last_success() {
    ROLLBACK_ATTEMPTED="true"
    ref_file="$DEPLOY_STATE_DIR/last_success.ref"
    env_file="$DEPLOY_STATE_DIR/last_success.env"
    if [ ! -s "$ref_file" ] || [ ! -s "$env_file" ]; then
        ROLLBACK_STATUS="unavailable"
        ROLLBACK_REASON="missing_last_success_state"
        log "No last-success deploy state exists; cannot rollback this first/unknown deploy."
        return 1
    fi

    previous_ref="$(sed -n '1p' "$ref_file")"
    if [ -z "$previous_ref" ]; then
        ROLLBACK_STATUS="unavailable"
        ROLLBACK_REASON="empty_last_success_ref"
        log "Last-success ref is empty; cannot rollback."
        return 1
    fi
    ROLLBACK_TARGET_REF="$previous_ref"
    if [ -s "$DEPLOY_STATE_DIR/last_success.profile" ]; then
        DEPLOY_PROFILE="$(sed -n '1p' "$DEPLOY_STATE_DIR/last_success.profile")"
        if [ -z "$DEPLOY_PROFILE" ]; then
            ROLLBACK_STATUS="unavailable"
            ROLLBACK_REASON="empty_last_success_profile"
            log "Last-success profile is empty; cannot rollback."
            return 1
        fi
    fi
    ROLLBACK_TARGET_PROFILE="$DEPLOY_PROFILE"
    ROLLBACK_TARGET_IMAGE="$(sed -n '1p' "$DEPLOY_STATE_DIR/last_success.image" 2>/dev/null || true)"
    ROLLBACK_TARGET_IMAGE_TAR="$(sed -n '1p' "$DEPLOY_STATE_DIR/last_success.image_tar" 2>/dev/null || true)"
    if [ -n "$ROLLBACK_TARGET_IMAGE_TAR" ] && [ -s "$ROLLBACK_TARGET_IMAGE_TAR" ]; then
        ROLLBACK_TARGET_IMAGE_TAR_PRESENT="true"
    else
        ROLLBACK_TARGET_IMAGE_TAR_PRESENT="false"
    fi

    log "Rolling back to previous successful ref $previous_ref with DEPLOY_PROFILE=$DEPLOY_PROFILE."
    if ! load_last_success_image_if_needed; then
        ROLLBACK_STATUS="failed"
        ROLLBACK_REASON="image_reload_failed"
        log "Rollback image reload failed."
        return 1
    fi

    if ! restore_last_success_checkout "$previous_ref"; then
        ROLLBACK_STATUS="failed"
        ROLLBACK_REASON="checkout_failed"
        log "Rollback checkout failed."
        return 1
    fi

    cp "$env_file" "$COMPOSE_ENV_FILE"
    chmod 600 "$COMPOSE_ENV_FILE"

    if ! deploy_env "$COMPOSE_ENV_FILE" false; then
        ROLLBACK_STATUS="failed"
        ROLLBACK_REASON="deploy_failed"
        log "Rollback deploy failed."
        return 1
    fi

    ROLLBACK_STATUS="succeeded"
    ROLLBACK_REASON=""
    log "Rollback succeeded; candidate failed but previous deploy was restored."
    return 0
}

if [ ! -f "$CANDIDATE_ENV_FILE" ]; then
    log "Candidate env file is missing: $CANDIDATE_ENV_FILE"
    exit 1
fi

candidate_ref="$(git rev-parse HEAD)"
log "Deploying candidate ref $candidate_ref with DEPLOY_PROFILE=$DEPLOY_PROFILE."

if deploy_env "$CANDIDATE_ENV_FILE" true; then
    RECORD_SUCCESS_STATUS="pending"
    if ! write_release_evidence "candidate" "candidate_gates_passed"; then
        RECORD_SUCCESS_STATUS="not_run"
        POST_GATE_FAILURE_CLASS="release_evidence_write_failed"
        log "Candidate deploy passed gates but release evidence recording failed; attempting rollback if possible."
    elif record_success; then
        RECORD_SUCCESS_STATUS="passed"
        if ! write_release_evidence "candidate" "success"; then
            log "Candidate deploy succeeded but final release evidence recording failed."
            exit 1
        fi
        log "Candidate deploy succeeded."
        exit 0
    else
        RECORD_SUCCESS_STATUS="failed"
        POST_GATE_FAILURE_CLASS="record_success_failed"
        write_release_evidence "candidate" "record_success_failed_rollback_pending" || true
        log "Candidate deploy succeeded but last-success state recording failed; attempting rollback if possible."
    fi
else
    POST_GATE_FAILURE_CLASS="candidate_failed"
    write_release_evidence "candidate" "candidate_failed_rollback_pending" || true
    log "Candidate deploy failed; attempting rollback if last-success state exists."
fi

if rollback_to_last_success; then
    if [ "$POST_GATE_FAILURE_CLASS" = "release_evidence_write_failed" ]; then
        write_release_evidence "rollback" "release_evidence_failed_rollback_succeeded" || true
    elif [ "$POST_GATE_FAILURE_CLASS" = "record_success_failed" ]; then
        write_release_evidence "rollback" "record_success_failed_rollback_succeeded" || true
    else
        write_release_evidence "rollback" "candidate_failed_rollback_succeeded" || true
    fi
    exit 1
fi

if [ "$POST_GATE_FAILURE_CLASS" = "release_evidence_write_failed" ]; then
    write_release_evidence "rollback" "release_evidence_failed_rollback_failed" || true
elif [ "$POST_GATE_FAILURE_CLASS" = "record_success_failed" ]; then
    write_release_evidence "rollback" "record_success_failed_rollback_failed" || true
else
    write_release_evidence "rollback" "candidate_failed_rollback_failed" || true
fi
log "Candidate deploy failed and rollback was unavailable or unsuccessful."
exit 1
