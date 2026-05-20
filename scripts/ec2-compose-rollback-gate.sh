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
        feature_source="feature_outputs"
    fi
    if [ "$feature_source" != "feature_outputs" ]; then
        log "live-product requires FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs, got $feature_source."
        return 1
    fi
}

run_db_release_preflight() {
    env_file="$1"
    service="$(db_preflight_service)"
    if [ -z "$service" ]; then
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
                return 1
            fi
            if ! validate_live_product_frontend_feature_source "$env_file"; then
                return 1
            fi
            db_url="$(env_file_value "$env_file" DB_WRITER_DATABASE_URL)"
            db_user="$(env_file_value "$env_file" DB_WRITER_DATABASE_USER)"
            db_password="$(env_file_value "$env_file" DB_WRITER_DATABASE_PASSWORD)"
            required="true"
            log "Running live-product Flyway migration against DB_WRITER_DATABASE_URL before release preflight."
            if ! compose_profile "$env_file" run --rm --no-deps -T db-migrate-live; then
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
        log "Skipping DB release preflight: candidate DB URL is empty and DEPLOY_DB_PREFLIGHT_REQUIRED=$required."
        return 0
    fi
    if [ -z "$db_url" ]; then
        log "DB release preflight required but candidate DB URL is empty."
        return 1
    fi

    log "Running DB release preflight with candidate service $service before stopping current services."
    if ! compose_profile "$env_file" run --rm --no-deps -T \
        -e DEPLOY_DB_PREFLIGHT_REQUIRED="$required" \
        -e DB_PREFLIGHT_DATABASE_URL="$db_url" \
        -e DB_PREFLIGHT_DATABASE_USER="$db_user" \
        -e DB_PREFLIGHT_DATABASE_PASSWORD="$db_password" \
        "$service" java -cp /app/app.jar edu.illinois.group8.storage.db.DbReleasePreflightCli; then
        log "DB release preflight failed."
        return 1
    fi
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

run_live_product_semantic_smoke() {
    env_file="$1"
    if [ "$DEPLOY_PROFILE" != "live-product" ]; then
        return 0
    fi

    if ! validate_live_product_frontend_feature_source "$env_file"; then
        return 1
    fi

    enabled="$(env_file_value "$env_file" LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED)"
    if [ -z "$enabled" ]; then
        enabled="$LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED"
    fi
    if ! is_true "$enabled"; then
        log "live-product semantic smoke must be enabled before recording a live-product deploy success."
        return 1
    fi

    log "Running live-product semantic smoke before recording candidate success."
    if ! COMPOSE_ENV_FILE="$env_file" \
        COMPOSE_PROFILE="$DEPLOY_PROFILE" \
        LIVE_PRODUCT_SMOKE_DOCKER_SUDO=true \
        FRONTEND_NO_PROXY="$FRONTEND_NO_PROXY" \
        sh scripts/live-product-smoke.sh; then
        log "live-product semantic smoke failed."
        return 1
    fi
}

deploy_env() {
    env_file="$1"
    candidate="$2"

    log "Validating Docker Compose config for DEPLOY_PROFILE=$DEPLOY_PROFILE."
    if ! compose_profile "$env_file" config --quiet; then
        log "Docker Compose config validation failed."
        return 1
    fi

    if ! build_or_verify_app_image "$env_file"; then
        return 1
    fi

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
    if ! down_all_profiles "$env_file"; then
        log "Docker Compose down failed."
        return 1
    fi

    log "Starting DEPLOY_PROFILE=$DEPLOY_PROFILE with prebuilt image."
    if ! compose_profile "$env_file" up -d --no-build --remove-orphans; then
        log "Docker Compose up failed."
        return 1
    fi

    if ! assert_runtime_container_images "$env_file"; then
        diagnose_profile "$env_file"
        return 1
    fi

    if ! profile_health_smoke; then
        diagnose_profile "$env_file"
        return 1
    fi

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
    ref_file="$DEPLOY_STATE_DIR/last_success.ref"
    env_file="$DEPLOY_STATE_DIR/last_success.env"
    if [ ! -s "$ref_file" ] || [ ! -s "$env_file" ]; then
        log "No last-success deploy state exists; cannot rollback this first/unknown deploy."
        return 1
    fi

    previous_ref="$(sed -n '1p' "$ref_file")"
    if [ -z "$previous_ref" ]; then
        log "Last-success ref is empty; cannot rollback."
        return 1
    fi
    if [ -s "$DEPLOY_STATE_DIR/last_success.profile" ]; then
        DEPLOY_PROFILE="$(sed -n '1p' "$DEPLOY_STATE_DIR/last_success.profile")"
        if [ -z "$DEPLOY_PROFILE" ]; then
            log "Last-success profile is empty; cannot rollback."
            return 1
        fi
    fi

    log "Rolling back to previous successful ref $previous_ref with DEPLOY_PROFILE=$DEPLOY_PROFILE."
    if ! load_last_success_image_if_needed; then
        log "Rollback image reload failed."
        return 1
    fi

    if ! restore_last_success_checkout "$previous_ref"; then
        log "Rollback checkout failed."
        return 1
    fi

    cp "$env_file" "$COMPOSE_ENV_FILE"
    chmod 600 "$COMPOSE_ENV_FILE"

    if ! deploy_env "$COMPOSE_ENV_FILE" false; then
        log "Rollback deploy failed."
        return 1
    fi

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
    if record_success; then
        log "Candidate deploy succeeded."
        exit 0
    fi
    log "Candidate deploy succeeded but last-success state recording failed; attempting rollback if possible."
else
    log "Candidate deploy failed; attempting rollback if last-success state exists."
fi

if rollback_to_last_success; then
    exit 1
fi

log "Candidate deploy failed and rollback was unavailable or unsuccessful."
exit 1
