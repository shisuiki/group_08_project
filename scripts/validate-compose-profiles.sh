#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT"

if ! command -v docker >/dev/null 2>&1; then
    printf 'docker command not found\n' >&2
    exit 127
fi

if ! docker compose version >/dev/null 2>&1; then
    printf 'docker compose plugin not available\n' >&2
    exit 127
fi

compose() {
    docker compose --env-file /dev/null "$@"
}

validate_config() {
    label="$1"
    shift
    compose "$@" config --quiet
    printf 'PASS compose_config profiles=%s\n' "$label"
}

services_for() {
    compose "$@" config --services
}

service_config_for() {
    service="$1"
    shift
    compose "$@" config "$service"
}

assert_services_absent() {
    label="$1"
    shift
    services="$(services_for "$@")"
    for service in stream-recorder s3-recording-sync; do
        if printf '%s\n' "$services" | grep -qx "$service"; then
            printf 'profile %s unexpectedly includes %s\n' "$label" "$service" >&2
            exit 1
        fi
    done
    printf 'PASS compose_services_absent profiles=%s services=stream-recorder,s3-recording-sync\n' "$label"
}

assert_services_present() {
    label="$1"
    shift
    services="$(services_for "$@")"
    for service in stream-recorder s3-recording-sync; do
        if ! printf '%s\n' "$services" | grep -qx "$service"; then
            printf 'profile %s is missing %s\n' "$label" "$service" >&2
            exit 1
        fi
    done
    printf 'PASS compose_services_present profiles=%s services=stream-recorder,s3-recording-sync\n' "$label"
}

assert_exact_services() {
    label="$1"
    expected="$2"
    shift 2
    services="$(services_for "$@")"
    for service in $expected; do
        if ! printf '%s\n' "$services" | grep -qx "$service"; then
            printf 'profile %s is missing %s\n' "$label" "$service" >&2
            exit 1
        fi
    done
    for service in $services; do
        case " $expected " in
            *" $service "*) ;;
            *)
                printf 'profile %s unexpectedly includes %s\n' "$label" "$service" >&2
                exit 1
                ;;
        esac
    done
    printf 'PASS compose_exact_services profiles=%s services=%s\n' "$label" "$expected"
}

assert_frontend_adapter_metadata_env_present() {
    label="$1"
    shift
    rendered="$(service_config_for frontend-adapter "$@")"
    for env_name in \
        FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_ENABLED \
        FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_INTERVAL_MS \
        FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_MAX_ROWS \
        FRONTEND_ADAPTER_METADATA_SOURCE \
        FRONTEND_ADAPTER_METADATA_MAX_ROWS \
        FRONTEND_ADAPTER_STATIC_ROOT; do
        if ! printf '%s\n' "$rendered" | grep -q "^      ${env_name}:"; then
            printf 'profile %s frontend-adapter is missing environment %s\n' "$label" "$env_name" >&2
            exit 1
        fi
    done
    printf 'PASS compose_service_environment profiles=%s service=frontend-adapter env=feature-output-refresh,metadata,static-root\n' "$label"
}

assert_db_primary_product_services_present() {
    assert_exact_services \
        "db-primary-product" \
        "timescaledb db-migrate featureplant-db-follower frontend-adapter-db-primary" \
        --profile db-primary-product
}

assert_live_product_services_present() {
    assert_exact_services \
        "live-product" \
        "node0 node1 node2 wsclient db-migrate-live streamtap featureplant-db-follower frontend-adapter-db-primary" \
        --profile live-product
}

assert_live_product_local_db_services_present() {
    assert_exact_services \
        "live-product-local-db" \
        "node0 node1 node2 wsclient timescaledb db-migrate streamtap featureplant-db-follower frontend-adapter-db-primary" \
        --profile live-product-local-db
}

assert_db_primary_product_defaults_aligned() {
    featureplant_rendered="$(service_config_for featureplant-db-follower --profile db-primary-product)"
    for expected in \
        "FEATUREPLANT_SOURCE: db" \
        "FEATUREPLANT_OUTPUT: db" \
        "FEATUREPLANT_DB_URL: jdbc:postgresql://timescaledb:5432/kalshi_test" \
        "FEATUREPLANT_DB_USER: kalshi" \
        "FEATUREPLANT_DB_INCLUDE_REPLAY: \"false\"" \
        "FEATUREPLANT_DB_CURSOR_NAME: db-primary-product-featureplant" \
        "FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED: \"true\"" \
        "FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY: \"250000\"" \
        "FEATUREPLANT_DB_OUTPUT_BATCH_SIZE: \"500\"" \
        "FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS: \"5000\"" \
        "FEATUREPLANT_RUN_ONCE: \"false\"" \
        "FEATUREPLANT_METRICS_HOST: 0.0.0.0" \
        "FEATUREPLANT_METRICS_PORT: \"8094\""; do
        if ! printf '%s\n' "$featureplant_rendered" | grep -q "^      ${expected}$"; then
            printf 'db-primary-product featureplant-db-follower missing default %s\n' "$expected" >&2
            exit 1
        fi
    done
    for expected in 'published: "8094"' 'target: 8094'; do
        if ! printf '%s\n' "$featureplant_rendered" | grep -q "^        ${expected}$"; then
            printf 'db-primary-product featureplant-db-follower missing metrics port %s\n' "$expected" >&2
            exit 1
        fi
    done
    for expected in \
        "FEATUREPLANT_METRICS_HOST_PORT: \${{ vars.FEATUREPLANT_METRICS_HOST_PORT || '8094' }}" \
        'FEATUREPLANT_METRICS_HOST_PORT=$FEATUREPLANT_METRICS_HOST_PORT'; do
        if ! grep -Fq "$expected" .github/workflows/deploy-ec2.yml; then
            printf 'deploy workflow missing featureplant metrics propagation: %s\n' "$expected" >&2
            exit 1
        fi
    done

    frontend_rendered="$(service_config_for frontend-adapter-db-primary --profile db-primary-product)"
    for expected in \
        "FRONTEND_ADAPTER_SOURCE: db" \
        "FRONTEND_ADAPTER_FEATURE_SOURCE: latest_market_state" \
        "FRONTEND_ADAPTER_FEATURE_OUTPUT_REFRESH_ENABLED: \"true\"" \
        "FRONTEND_ADAPTER_STATIC_ROOT: /app/frontend/tradingview-lightweight" \
        "FRONTEND_ADAPTER_DB_URL: jdbc:postgresql://timescaledb:5432/kalshi_test" \
        "FRONTEND_ADAPTER_DB_USER: kalshi" \
        "FRONTEND_ADAPTER_DB_PASSWORD: kalshi"; do
        if ! printf '%s\n' "$frontend_rendered" | grep -q "^      ${expected}$"; then
            printf 'db-primary-product frontend-adapter-db-primary missing default %s\n' "$expected" >&2
            exit 1
        fi
    done
    for expected in 'published: "8090"' 'target: 8090'; do
        if ! printf '%s\n' "$frontend_rendered" | grep -q "^        ${expected}$"; then
            printf 'db-primary-product frontend-adapter-db-primary missing frontend port %s\n' "$expected" >&2
            exit 1
        fi
    done
    frontend_override_rendered="$(
        DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT=18090 service_config_for frontend-adapter-db-primary --profile db-primary-product
    )"
    if ! printf '%s\n' "$frontend_override_rendered" | grep -q '^        published: "18090"$'; then
        printf 'db-primary-product frontend-adapter-db-primary host port override did not render as 18090\n' >&2
        exit 1
    fi
    for expected in \
        "DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT: \${{ vars.DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT || '8090' }}" \
        "LOCAL_DB_NAME: \${{ vars.LOCAL_DB_NAME || 'kalshi_test' }}" \
        "LOCAL_DB_USER: \${{ vars.LOCAL_DB_USER || 'kalshi' }}" \
        "LOCAL_DB_PASSWORD: \${{ secrets.LOCAL_DB_PASSWORD || secrets.DB_WRITER_DATABASE_PASSWORD || 'kalshi' }}" \
        "FRONTEND_ADAPTER_DB_URL: \${{ vars.FRONTEND_ADAPTER_DB_URL || vars.DB_WRITER_DATABASE_URL }}" \
        "FRONTEND_ADAPTER_DB_USER: \${{ vars.FRONTEND_ADAPTER_DB_USER || vars.DB_WRITER_DATABASE_USER }}" \
        "FRONTEND_ADAPTER_DB_PASSWORD: \${{ secrets.FRONTEND_ADAPTER_DB_PASSWORD || secrets.DB_WRITER_DATABASE_PASSWORD }}" \
        "FRONTEND_ADAPTER_FEATURE_SOURCE: \${{ vars.FRONTEND_ADAPTER_FEATURE_SOURCE || 'latest_market_state' }}" \
        'DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT=$DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT' \
        'LOCAL_DB_NAME=$LOCAL_DB_NAME' \
        'LOCAL_DB_USER=$LOCAL_DB_USER' \
        'LOCAL_DB_PASSWORD=$LOCAL_DB_PASSWORD' \
        'FRONTEND_ADAPTER_DB_URL=$EFFECTIVE_FRONTEND_ADAPTER_DB_URL' \
        'FRONTEND_ADAPTER_DB_USER=$EFFECTIVE_FRONTEND_ADAPTER_DB_USER' \
        'FRONTEND_ADAPTER_DB_PASSWORD=$EFFECTIVE_FRONTEND_ADAPTER_DB_PASSWORD' \
        'FRONTEND_ADAPTER_FEATURE_SOURCE=$FRONTEND_ADAPTER_FEATURE_SOURCE' \
        'EFFECTIVE_BACKEND_PROFILE=production' \
        'EFFECTIVE_BACKEND_PROFILE=recording-capture' \
        'BACKEND_PROFILE=$EFFECTIVE_BACKEND_PROFILE' \
        'DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT=$q_db_primary_product_frontend_host_port'; do
        if ! grep -Fq "$expected" .github/workflows/deploy-ec2.yml; then
            printf 'deploy workflow missing db-primary-product frontend propagation: %s\n' "$expected" >&2
            exit 1
        fi
    done
    printf 'PASS db_primary_product_defaults featureplant=follower frontend=latest_market_state\n'
}

assert_frontend_adapter_db_primary_static_root() {
    for profile in db-primary-product live-product live-product-local-db; do
        rendered="$(service_config_for frontend-adapter-db-primary --profile "$profile")"
        if ! printf '%s\n' "$rendered" \
            | grep -q '^      FRONTEND_ADAPTER_STATIC_ROOT: /app/frontend/tradingview-lightweight$'; then
            printf '%s frontend-adapter-db-primary missing static root default\n' "$profile" >&2
            exit 1
        fi
        if ! printf '%s\n' "$rendered" \
            | grep -q '^      FRONTEND_ADAPTER_FEATURE_SOURCE: latest_market_state$'; then
            printf '%s frontend-adapter-db-primary missing latest_market_state default\n' "$profile" >&2
            exit 1
        fi
        fallback_rendered="$(
            FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs \
                service_config_for frontend-adapter-db-primary --profile "$profile"
        )"
        if ! printf '%s\n' "$fallback_rendered" \
            | grep -q '^      FRONTEND_ADAPTER_FEATURE_SOURCE: feature_outputs$'; then
            printf '%s frontend-adapter-db-primary missing feature_outputs fallback override\n' "$profile" >&2
            exit 1
        fi
    done
    printf 'PASS frontend_adapter_db_primary_frontend_contract\n'
}

assert_frontend_release_health_contract() {
    for service_profile in \
        "frontend-adapter-db-primary:--profile db-primary-product" \
        "frontend-adapter:--profile local-db --profile frontend-integration"; do
        service="${service_profile%%:*}"
        profile_args="${service_profile#*:}"
        rendered="$(service_config_for "$service" $profile_args)"
        for env_name in \
            KALSHI_RELEASE_SHA \
            KALSHI_APP_IMAGE \
            KALSHI_DEPLOY_PROFILE \
            KALSHI_GITHUB_RUN_ID \
            KALSHI_GITHUB_RUN_ATTEMPT; do
            if ! printf '%s\n' "$rendered" | grep -q "^      ${env_name}:"; then
                printf '%s missing release environment %s\n' "$service" "$env_name" >&2
                exit 1
            fi
        done
        rendered_default_image="$(KALSHI_APP_IMAGE= service_config_for "$service" $profile_args)"
        if ! printf '%s\n' "$rendered_default_image" | grep -q '^      KALSHI_APP_IMAGE: kalshi-project:local$'; then
            printf '%s must expose the compose default app image in release health\n' "$service" >&2
            exit 1
        fi
    done

    for expected in \
        'KALSHI_RELEASE_SHA: ${{ github.sha }}' \
        "KALSHI_DEPLOY_PROFILE: \${{ github.event_name == 'workflow_dispatch' && inputs.deploy_profile || vars.DEPLOY_PROFILE || 'cluster-live' }}" \
        'KALSHI_GITHUB_RUN_ID: ${{ github.run_id }}' \
        'KALSHI_GITHUB_RUN_ATTEMPT: ${{ github.run_attempt }}' \
        'KALSHI_RELEASE_SHA=$KALSHI_RELEASE_SHA' \
        'KALSHI_DEPLOY_PROFILE=$KALSHI_DEPLOY_PROFILE' \
        'KALSHI_GITHUB_RUN_ID=$KALSHI_GITHUB_RUN_ID' \
        'KALSHI_GITHUB_RUN_ATTEMPT=$KALSHI_GITHUB_RUN_ATTEMPT' \
        'EXPECTED_KALSHI_RELEASE_SHA=$q_kalshi_release_sha' \
        'EXPECTED_KALSHI_APP_IMAGE=$q_kalshi_app_image' \
        'EXPECTED_KALSHI_DEPLOY_PROFILE=$q_deploy_profile'; do
        if ! grep -Fq "$expected" .github/workflows/deploy-ec2.yml; then
            printf 'deploy workflow missing frontend release health contract: %s\n' "$expected" >&2
            exit 1
        fi
    done

    for smoke_script in scripts/db-primary-demo-smoke.sh scripts/live-product-smoke.sh; do
        for expected in \
            'EXPECTED_KALSHI_RELEASE_SHA' \
            'EXPECTED_KALSHI_APP_IMAGE' \
            'EXPECTED_KALSHI_DEPLOY_PROFILE' \
            'EXPECTED_KALSHI_GITHUB_RUN_ID' \
            'EXPECTED_KALSHI_GITHUB_RUN_ATTEMPT' \
            'health check failed: release is missing' \
            'feature_source' \
            'expected_feature_source' \
            'freshness = body.get("data_freshness")' \
            'latest_event_ts_ms' \
            'latest_event_age_ms' \
            'release-identity' \
            'health-data-age' \
            'quote-update-health' \
            '/quotes/stream' \
            'curl -fsS -N --max-time 3' \
            'SSE data event' \
            'server_ts_ms' \
            'changed' \
            'quotes_stream' \
            'EventSource' \
            'body.quote_streams' \
            'body.quote_updates'; do
            if ! grep -Fq "$expected" "$smoke_script"; then
                printf '%s missing release/data freshness contract: %s\n' "$smoke_script" "$expected" >&2
                exit 1
            fi
        done
    done

    for expected in \
        'id="release-identity"' \
        'id="health-data-age"' \
        'id="quote-update-health"' \
        'body.release' \
        'body.data_freshness' \
        'body.quote_streams' \
        'body.quote_updates' \
        '/quotes/stream' \
        'EventSource' \
        'latest_event_ts_ms'; do
        if ! grep -Fq "$expected" frontend/tradingview-lightweight/index.html frontend/tradingview-lightweight/app.js; then
            printf 'frontend static UI missing release/data freshness contract: %s\n' "$expected" >&2
            exit 1
        fi
    done
    printf 'PASS frontend_release_health_contract\n'
}

assert_cluster_live_db_writer_stays_opt_in() {
    services="$(services_for --profile cluster-live)"
    for service in timescaledb db-migrate db-migrate-live featureplant-db-follower frontend-adapter-db-primary; do
        if printf '%s\n' "$services" | grep -qx "$service"; then
            printf 'cluster-live unexpectedly includes %s\n' "$service" >&2
            exit 1
        fi
    done
    rendered="$(service_config_for wsclient --profile cluster-live)"
    for expected in \
        'DB_WRITER_ENABLED: ""' \
        'DB_WRITER_DATABASE_URL: ""' \
        'DB_WRITER_DATABASE_USER: ""' \
        'DB_WRITER_DATABASE_PASSWORD: ""'; do
        if ! printf '%s\n' "$rendered" | grep -q "^      ${expected}$"; then
            printf 'cluster-live wsclient DB writer default changed: %s\n' "$expected" >&2
            exit 1
        fi
    done
    printf 'PASS cluster_live_db_writer_opt_in\n'
}

assert_live_product_db_writer_expectations() {
    live_db_url="jdbc:postgresql://live-db.example.internal:5432/kalshi_live"
    live_db_user="kalshi_live_user"
    live_db_password="kalshi-live-password"
    rendered_wsclient="$(
        DB_WRITER_ENABLED=true \
        DB_WRITER_DATABASE_URL="$live_db_url" \
        DB_WRITER_DATABASE_USER="$live_db_user" \
        DB_WRITER_DATABASE_PASSWORD="$live_db_password" \
        service_config_for wsclient --profile live-product
    )"
    for expected in \
        'DB_WRITER_ENABLED: "true"' \
        "DB_WRITER_DATABASE_URL: $live_db_url" \
        "DB_WRITER_DATABASE_USER: $live_db_user" \
        "DB_WRITER_DATABASE_PASSWORD: $live_db_password"; do
        if ! printf '%s\n' "$rendered_wsclient" | grep -q "^      ${expected}$"; then
            printf 'live-product wsclient missing DB writer setting %s\n' "$expected" >&2
            exit 1
        fi
    done

    rendered_featureplant="$(
        DB_WRITER_DATABASE_URL="$live_db_url" \
        DB_WRITER_DATABASE_USER="$live_db_user" \
        DB_WRITER_DATABASE_PASSWORD="$live_db_password" \
        service_config_for featureplant-db-follower --profile live-product
    )"
    for expected in \
        "FEATUREPLANT_DB_URL: $live_db_url" \
        "FEATUREPLANT_DB_USER: $live_db_user" \
        "FEATUREPLANT_DB_PASSWORD: $live_db_password"; do
        if ! printf '%s\n' "$rendered_featureplant" | grep -q "^      ${expected}$"; then
            printf 'live-product featureplant-db-follower missing external DB setting %s\n' "$expected" >&2
            exit 1
        fi
    done

    rendered_frontend="$(
        DB_WRITER_DATABASE_URL="$live_db_url" \
        DB_WRITER_DATABASE_USER="$live_db_user" \
        DB_WRITER_DATABASE_PASSWORD="$live_db_password" \
        service_config_for frontend-adapter-db-primary --profile live-product
    )"
    for expected in \
        "FRONTEND_ADAPTER_DB_URL: $live_db_url" \
        "FRONTEND_ADAPTER_DB_USER: $live_db_user" \
        "FRONTEND_ADAPTER_DB_PASSWORD: $live_db_password"; do
        if ! printf '%s\n' "$rendered_frontend" | grep -q "^      ${expected}$"; then
            printf 'live-product frontend-adapter-db-primary missing external DB setting %s\n' "$expected" >&2
            exit 1
        fi
    done

    rendered_migration="$(
        DB_WRITER_DATABASE_URL="$live_db_url" \
        DB_WRITER_DATABASE_USER="$live_db_user" \
        DB_WRITER_DATABASE_PASSWORD="$live_db_password" \
        service_config_for db-migrate-live --profile live-product
    )"
    for expected in \
        "FLYWAY_URL: $live_db_url" \
        "FLYWAY_USER: $live_db_user" \
        "FLYWAY_PASSWORD: $live_db_password"; do
        if ! printf '%s\n' "$rendered_migration" | grep -q "^      ${expected}$"; then
            printf 'live-product db-migrate-live missing external DB setting %s\n' "$expected" >&2
            exit 1
        fi
    done

    live_services="$(services_for --profile live-product)"
    for service in timescaledb db-migrate; do
        if printf '%s\n' "$live_services" | grep -qx "$service"; then
            printf 'live-product must not include local DB service %s\n' "$service" >&2
            exit 1
        fi
    done

    for expected in \
        'live-product requires DB_WRITER_ENABLED=true.' \
        'live-product requires DB_WRITER_DATABASE_URL, DB_WRITER_DATABASE_USER, and DB_WRITER_DATABASE_PASSWORD.' \
        'live-product requires DB writer, FeaturePlant, and frontend DB URLs to match.' \
        'live-product requires DB writer, FeaturePlant, and frontend DB users to match.' \
        'live-product requires DB writer, FeaturePlant, and frontend DB passwords to match.' \
        'live-product requires FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs or latest_market_state' \
        'Running live-product Flyway migration against DB_WRITER_DATABASE_URL before release preflight.' \
        'compose_profile "$env_file" run --rm --no-deps -T db-migrate-live' \
        'validate_live_product_frontend_feature_source()' \
        'FRONTEND_NO_PROXY="${FRONTEND_NO_PROXY:-127.0.0.1,localhost}"' \
        'curl -fsS --noproxy "$FRONTEND_NO_PROXY"' \
        'LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED="${LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED:-true}"' \
        'run_live_product_semantic_smoke "$env_file"' \
        'live-product semantic smoke must be enabled before recording a live-product deploy success.' \
        'LIVE_PRODUCT_SMOKE_DOCKER_SUDO=true' \
        'sh scripts/live-product-smoke.sh' \
        "live-product) printf '%s\\n' wsclient" \
        'wsclient "http://127.0.0.1:${WSCLIENT_METRICS_HOST_PORT}/health"' \
        'streamtap "http://127.0.0.1:${STREAM_TAP_HOST_PORT}/health"' \
        'featureplant-db-follower "http://127.0.0.1:${FEATUREPLANT_METRICS_HOST_PORT}/health"' \
        'frontend-adapter-db-primary "http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT}/health"' \
        'node0 node1 node2 wsclient streamtap'; do
        if ! grep -Fq "$expected" scripts/ec2-compose-rollback-gate.sh; then
            printf 'rollback gate missing live-product behavior: %s\n' "$expected" >&2
            exit 1
        fi
    done
    printf 'PASS live_product_db_writer_expectations\n'
}

assert_live_product_local_db_writer_expectations() {
    local_db_url="jdbc:postgresql://timescaledb:5432/kalshi_test"
    local_db_user="kalshi"
    local_db_password="kalshi"
    rendered_wsclient="$(
        DB_WRITER_ENABLED=true \
        DB_WRITER_DATABASE_URL="$local_db_url" \
        DB_WRITER_DATABASE_USER="$local_db_user" \
        DB_WRITER_DATABASE_PASSWORD="$local_db_password" \
        service_config_for wsclient --profile live-product-local-db
    )"
    for expected in \
        'DB_WRITER_ENABLED: "true"' \
        "DB_WRITER_DATABASE_URL: $local_db_url" \
        "DB_WRITER_DATABASE_USER: $local_db_user" \
        "DB_WRITER_DATABASE_PASSWORD: $local_db_password"; do
        if ! printf '%s\n' "$rendered_wsclient" | grep -q "^      ${expected}$"; then
            printf 'live-product-local-db wsclient missing local DB writer setting %s\n' "$expected" >&2
            exit 1
        fi
    done

    rendered_featureplant="$(
        DB_WRITER_DATABASE_URL="$local_db_url" \
        DB_WRITER_DATABASE_USER="$local_db_user" \
        DB_WRITER_DATABASE_PASSWORD="$local_db_password" \
        service_config_for featureplant-db-follower --profile live-product-local-db
    )"
    for expected in \
        "FEATUREPLANT_DB_URL: $local_db_url" \
        "FEATUREPLANT_DB_USER: $local_db_user" \
        "FEATUREPLANT_DB_PASSWORD: $local_db_password"; do
        if ! printf '%s\n' "$rendered_featureplant" | grep -q "^      ${expected}$"; then
            printf 'live-product-local-db featureplant-db-follower missing local DB setting %s\n' "$expected" >&2
            exit 1
        fi
    done

    rendered_frontend="$(
        DB_WRITER_DATABASE_URL="$local_db_url" \
        DB_WRITER_DATABASE_USER="$local_db_user" \
        DB_WRITER_DATABASE_PASSWORD="$local_db_password" \
        service_config_for frontend-adapter-db-primary --profile live-product-local-db
    )"
    for expected in \
        "FRONTEND_ADAPTER_DB_URL: $local_db_url" \
        "FRONTEND_ADAPTER_DB_USER: $local_db_user" \
        "FRONTEND_ADAPTER_DB_PASSWORD: $local_db_password"; do
        if ! printf '%s\n' "$rendered_frontend" | grep -q "^      ${expected}$"; then
            printf 'live-product-local-db frontend-adapter-db-primary missing local DB setting %s\n' "$expected" >&2
            exit 1
        fi
    done

    rendered_migration="$(service_config_for db-migrate --profile live-product-local-db)"
    for expected in \
        "FLYWAY_URL: $local_db_url" \
        "FLYWAY_USER: $local_db_user" \
        "FLYWAY_PASSWORD: $local_db_password"; do
        if ! printf '%s\n' "$rendered_migration" | grep -q "^      ${expected}$"; then
            printf 'live-product-local-db db-migrate missing local DB setting %s\n' "$expected" >&2
            exit 1
        fi
    done

    local_services="$(services_for --profile live-product-local-db)"
    for service in db-migrate-live stream-recorder s3-recording-sync; do
        if printf '%s\n' "$local_services" | grep -qx "$service"; then
            printf 'live-product-local-db must not include %s\n' "$service" >&2
            exit 1
        fi
    done

    for expected in \
        'validate_live_product_local_db_writer()' \
        'live-product-local-db requires DB_WRITER_ENABLED=true.' \
        'live-product-local-db requires DB writer to use local Timescale/Postgres settings.' \
        'live-product-local-db requires DB writer, FeaturePlant, and frontend DB URLs to match.' \
        'live-product-local-db requires DB writer, FeaturePlant, and frontend DB users to match.' \
        'live-product-local-db requires DB writer, FeaturePlant, and frontend DB passwords to match.' \
        'Running live-product-local-db Flyway migration against local Timescale before release preflight.' \
        'compose_profile "$env_file" run --rm -T db-migrate' \
        'live-product|live-product-local-db)' \
        'live-product-local-db) printf '\''%s\n'\'' wsclient' \
        'live-product|live-product-local-db)' \
        'COMPOSE_PROFILE="$DEPLOY_PROFILE"' \
        'node0 node1 node2 wsclient streamtap featureplant-db-follower frontend-adapter-db-primary'; do
        if ! grep -Fq "$expected" scripts/ec2-compose-rollback-gate.sh; then
            printf 'rollback gate missing live-product-local-db behavior: %s\n' "$expected" >&2
            exit 1
        fi
    done
    for expected in \
        'DB_WRITER_DATABASE_URL="$LOCAL_DB_URL"' \
        'FEATUREPLANT_DB_URL="$LOCAL_DB_URL"' \
        'FRONTEND_ADAPTER_DB_URL="$LOCAL_DB_URL"' \
        'LIVE_PRODUCT_SMOKE_DB_URL="$LOCAL_DB_URL"'; do
        if ! grep -Fq "$expected" scripts/live-product-smoke.sh; then
            printf 'live-product smoke missing local DB pin for live-product-local-db: %s\n' "$expected" >&2
            exit 1
        fi
    done

    tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/live-product-local-db-contract.XXXXXX")"
    rollback_functions="$tmpdir/rollback-functions.sh"
    local_env="$tmpdir/local.env"
    remote_env="$tmpdir/remote.env"
    sed '/^if \[ ! -f "\$CANDIDATE_ENV_FILE" \]; then$/,$d' scripts/ec2-compose-rollback-gate.sh > "$rollback_functions"
    cat > "$local_env" <<EOF
DB_WRITER_ENABLED=true
DB_WRITER_DATABASE_URL=$local_db_url
DB_WRITER_DATABASE_USER=$local_db_user
DB_WRITER_DATABASE_PASSWORD=$local_db_password
FEATUREPLANT_DB_URL=$local_db_url
FEATUREPLANT_DB_USER=$local_db_user
FEATUREPLANT_DB_PASSWORD=$local_db_password
FRONTEND_ADAPTER_DB_URL=$local_db_url
FRONTEND_ADAPTER_DB_USER=$local_db_user
FRONTEND_ADAPTER_DB_PASSWORD=$local_db_password
LOCAL_DB_NAME=kalshi_test
LOCAL_DB_USER=$local_db_user
LOCAL_DB_PASSWORD=$local_db_password
EOF
    cat > "$remote_env" <<'EOF'
DB_WRITER_ENABLED=true
DB_WRITER_DATABASE_URL=jdbc:postgresql://remote.example.internal:5432/kalshi_live
DB_WRITER_DATABASE_USER=remote
DB_WRITER_DATABASE_PASSWORD=remote
FEATUREPLANT_DB_URL=jdbc:postgresql://remote.example.internal:5432/kalshi_live
FEATUREPLANT_DB_USER=remote
FEATUREPLANT_DB_PASSWORD=remote
FRONTEND_ADAPTER_DB_URL=jdbc:postgresql://remote.example.internal:5432/kalshi_live
FRONTEND_ADAPTER_DB_USER=remote
FRONTEND_ADAPTER_DB_PASSWORD=remote
LOCAL_DB_NAME=kalshi_test
LOCAL_DB_USER=kalshi
LOCAL_DB_PASSWORD=kalshi
EOF
    if ! (
        DEPLOY_PROFILE=live-product-local-db
        CANDIDATE_ENV_FILE="$local_env"
        COMPOSE_ENV_FILE="$local_env"
        . "$rollback_functions"
        validate_live_product_local_db_writer "$local_env"
    ) >/dev/null 2>&1; then
        rm -rf "$tmpdir"
        printf 'live-product-local-db validator rejected matching local DB settings\n' >&2
        exit 1
    fi
    if (
        DEPLOY_PROFILE=live-product-local-db
        CANDIDATE_ENV_FILE="$remote_env"
        COMPOSE_ENV_FILE="$remote_env"
        . "$rollback_functions"
        validate_live_product_local_db_writer "$remote_env"
    ) >/dev/null 2>&1; then
        rm -rf "$tmpdir"
        printf 'live-product-local-db validator accepted remote DB settings\n' >&2
        exit 1
    fi
    rm -rf "$tmpdir"
    printf 'PASS live_product_local_db_writer_expectations\n'
}

assert_kalshi_app_image_contract() {
    app_image="example/kalshi:aj"
    app_services="
        node0:cluster-live
        node0-capture:recording-capture
        node1:cluster-live
        node2:cluster-live
        wsclient:cluster-live
        wsclient-capture:recording-capture
        raw-ingress-replay:raw-replay
        historical-backfill:historical-backfill
        streamtap:cluster-live
        stream-recorder:recording-capture
        featureplant:featureplant
        featureplant-db-follower:db-primary-product
        frontend-adapter-db-primary:db-primary-product
        frontend-adapter:frontend-integration
    "
    for service_profile in $app_services; do
        service="${service_profile%%:*}"
        profile="${service_profile#*:}"
        rendered="$(KALSHI_APP_IMAGE="$app_image" service_config_for "$service" --profile "$profile")"
        if ! printf '%s\n' "$rendered" | grep -q "^    image: $app_image$"; then
            printf 'profile %s service %s did not render KALSHI_APP_IMAGE image %s\n' \
                "$profile" "$service" "$app_image" >&2
            exit 1
        fi
        if ! printf '%s\n' "$rendered" | grep -Fq "      context: $REPO_ROOT"; then
            printf 'profile %s service %s no longer builds from repo root\n' "$profile" "$service" >&2
            exit 1
        fi
    done

    s3_compose_block="$(awk '
        /^  s3-recording-sync:$/ { capture = 1; print; next }
        capture && /^  [A-Za-z0-9_.-]+:$/ { exit }
        capture { print }
    ' docker-compose.yml)"
    if printf '%s\n' "$s3_compose_block" | grep -q 'KALSHI_APP_IMAGE'; then
        printf 's3-recording-sync must not use KALSHI_APP_IMAGE\n' >&2
        exit 1
    fi
    for expected in \
        '    image: group_08_project-s3-recording-sync' \
        '      dockerfile: ops/docker/s3-recording-sync.Dockerfile'; do
        if ! printf '%s\n' "$s3_compose_block" | grep -Fq "$expected"; then
            printf 's3-recording-sync missing dedicated image/build contract: %s\n' "$expected" >&2
            exit 1
        fi
    done

    for expected in \
        'KALSHI_APP_IMAGE: kalshi-project:${{ github.sha }}' \
        'KALSHI_APP_IMAGE_TAR: .deploy-state/images/kalshi-project-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}.tar.gz' \
        'docker save "$KALSHI_APP_IMAGE"' \
        'actions/upload-artifact@v6' \
        'actions/download-artifact@v7' \
        'mkdir -p '\''$DEPLOY_PATH/.deploy-state/images'\''' \
        'scp -i ~/.ssh/ec2_key "$image_tar" "$EC2_USER@$EC2_HOST:$DEPLOY_PATH/$KALSHI_APP_IMAGE_TAR"' \
        'IMAGE_TAR="\$APP_DIR/$KALSHI_APP_IMAGE_TAR"' \
        'LAST_SUCCESS_ENV="\$STATE_DIR/last_success.env"' \
        'previous_image="\$(sed -n '\''s/^KALSHI_APP_IMAGE=//p'\'' "\$LAST_SUCCESS_ENV" | tail -n 1)"' \
        'sudo docker save "\$previous_image" | gzip -1 > "\$previous_tar"' \
        'printf '\''%s\n'\'' "\$previous_tar_rel" > "\$LAST_SUCCESS_IMAGE_TAR"' \
        'gzip -t "\$IMAGE_TAR"' \
        'sudo docker load' \
        'sudo docker image inspect "$KALSHI_APP_IMAGE"' \
        'KALSHI_APP_IMAGE=$KALSHI_APP_IMAGE' \
        'KALSHI_APP_IMAGE_TAR=$KALSHI_APP_IMAGE_TAR' \
        'printf -v q_candidate_image_tar '\''%q'\'' "$KALSHI_APP_IMAGE_TAR"' \
        'KALSHI_APP_IMAGE=$q_kalshi_app_image' \
        'CANDIDATE_IMAGE_TAR=$q_candidate_image_tar'; do
        if ! grep -Fq "$expected" .github/workflows/deploy-ec2.yml; then
            printf 'deploy workflow missing immutable app image contract: %s\n' "$expected" >&2
            exit 1
        fi
    done
    if grep -Fq 'rm -f "\$IMAGE_TAR"' .github/workflows/deploy-ec2.yml; then
        printf 'deploy workflow must retain EC2 image tar after docker load\n' >&2
        exit 1
    fi

    for expected in \
        'compose_app_image()' \
        'env_file_value "$env_file" KALSHI_APP_IMAGE' \
        'compose_app_image_tar()' \
        'env_file_value "$env_file" KALSHI_APP_IMAGE_TAR' \
        'CANDIDATE_IMAGE_TAR="${CANDIDATE_IMAGE_TAR:-}"' \
        'build_or_verify_app_image()' \
        'sudo docker image inspect "$app_image"' \
        'load_app_image_from_tar "$app_image" "$image_tar"' \
        'last_success.image' \
        'last_success.image_tar' \
        'previous_image_file="$DEPLOY_STATE_DIR/last_success.image"' \
        'previous_image_tar_file="$DEPLOY_STATE_DIR/last_success.image_tar"' \
        'load_last_success_image_if_needed()' \
        'load_app_image_from_tar "$previous_image" "$previous_image_tar"' \
        'tmp_prefix="$DEPLOY_STATE_DIR/.last_success.$$"' \
        'chmod 600 "$tmp_ref" "$tmp_env" "$tmp_profile"' \
        'mv "$tmp_ref" "$DEPLOY_STATE_DIR/last_success.ref"' \
        'mv "$tmp_env" "$DEPLOY_STATE_DIR/last_success.env"' \
        'mv "$tmp_profile" "$DEPLOY_STATE_DIR/last_success.profile"' \
        'mv "$tmp_image" "$DEPLOY_STATE_DIR/last_success.image"' \
        'mv "$tmp_image_tar" "$DEPLOY_STATE_DIR/last_success.image_tar"' \
        'assert_runtime_container_images()' \
        'sudo docker inspect -f '\''{{.Config.Image}}'\''' \
        'expected_image="$app_image"' \
        'container image mismatch' \
        'assert_runtime_container_images "$env_file"' \
        'skipping Docker Compose build' \
        'if ! build_or_verify_app_image "$env_file"; then' \
        'if ! compose_profile "$env_file" build; then'; do
        if ! grep -Fq "$expected" scripts/ec2-compose-rollback-gate.sh; then
            printf 'rollback gate missing immutable app image behavior: %s\n' "$expected" >&2
            exit 1
        fi
    done

    printf 'PASS kalshi_app_image_contract services=java-apps image=%s\n' "$app_image"
}

assert_published_ports_loopback() {
    label="$1"
    shift
    if failures=$(COMPOSE_HOST_BIND_IP= compose "$@" config | awk '
        function reset_port() {
            host_ip = ""
            published = ""
        }
        function trim_quotes(value) {
            gsub(/^"/, "", value)
            gsub(/"$/, "", value)
            return value
        }
        function check_port() {
            if (published != "" && host_ip != "127.0.0.1") {
                printf "%s published=%s host_ip=%s\n", service, published, (host_ip == "" ? "<missing>" : host_ip)
                bad = 1
            }
            reset_port()
        }
        /^  [A-Za-z0-9_.-][A-Za-z0-9_.-]*:$/ {
            if (in_ports) {
                check_port()
            }
            service = $1
            sub(/:$/, "", service)
            in_ports = 0
            reset_port()
            next
        }
        /^    ports:$/ {
            in_ports = 1
            reset_port()
            next
        }
        in_ports && /^    [A-Za-z0-9_.-][A-Za-z0-9_.-]*:/ {
            check_port()
            in_ports = 0
            next
        }
        in_ports && /^      - / {
            check_port()
            next
        }
        in_ports && /^        host_ip:/ {
            host_ip = trim_quotes($2)
            next
        }
        in_ports && /^        published:/ {
            published = trim_quotes($2)
            next
        }
        END {
            if (in_ports) {
                check_port()
            }
            exit bad
        }
    '); then
        printf 'PASS compose_published_ports_loopback profiles=%s host_ip=127.0.0.1\n' "$label"
    else
        printf 'profile %s has published ports not bound to 127.0.0.1:\n%s\n' "$label" "$failures" >&2
        exit 1
    fi
}

assert_no_default_network() {
    label="$1"
    shift
    if compose "$@" config | grep -q '^  default:$'; then
        printf 'profile %s renders implicit default network; attach all services to an explicit network\n' "$label" >&2
        exit 1
    fi
    printf 'PASS compose_no_default_network profiles=%s\n' "$label"
}

assert_raw_replay_table_defaults_aligned() {
    rendered="$(service_config_for raw-ingress-replay --profile raw-replay)"
    if ! printf '%s\n' "$rendered" | grep -q '^      RAW_REPLAY_TABLE: raw_ws_events$'; then
        printf 'raw-ingress-replay default RAW_REPLAY_TABLE is not raw_ws_events\n' >&2
        exit 1
    fi
    if ! grep -Fq "RAW_REPLAY_TABLE: \${{ vars.RAW_REPLAY_TABLE || 'raw_ws_events' }}" .github/workflows/deploy-ec2.yml; then
        printf 'deploy workflow RAW_REPLAY_TABLE fallback is not raw_ws_events\n' >&2
        exit 1
    fi
    if ! grep -Fq 'DEFAULT_RAW_TABLE = "raw_ws_events"' \
        src/main/java/edu/illinois/group8/replay/raw/RawIngressReplayConfig.java; then
        printf 'RawIngressReplayConfig DEFAULT_RAW_TABLE is not raw_ws_events\n' >&2
        exit 1
    fi
    printf 'PASS raw_replay_table_defaults table=raw_ws_events\n'
}

assert_ws_reconnect_defaults_aligned() {
    for service_profile in "wsclient:cluster-live" "wsclient-capture:recording-capture"; do
        service="${service_profile%%:*}"
        profile="${service_profile#*:}"
        rendered="$(service_config_for "$service" --profile "$profile")"
        for expected in \
            "BACKEND_WS_RECONNECT_ENABLED: \"true\"" \
            "BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS: \"1000\"" \
            "BACKEND_WS_RECONNECT_MAX_BACKOFF_MS: \"30000\"" \
            "BACKEND_WS_RECONNECT_MAX_ATTEMPTS: \"0\""; do
            if ! printf '%s\n' "$rendered" | grep -q "^      ${expected}$"; then
                printf 'profile %s service %s missing reconnect default %s\n' "$profile" "$service" "$expected" >&2
                exit 1
            fi
        done
    done
    for fallback in \
        "BACKEND_WS_RECONNECT_ENABLED: \${{ vars.BACKEND_WS_RECONNECT_ENABLED || 'true' }}" \
        "BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS: \${{ vars.BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS || '1000' }}" \
        "BACKEND_WS_RECONNECT_MAX_BACKOFF_MS: \${{ vars.BACKEND_WS_RECONNECT_MAX_BACKOFF_MS || '30000' }}" \
        "BACKEND_WS_RECONNECT_MAX_ATTEMPTS: \${{ vars.BACKEND_WS_RECONNECT_MAX_ATTEMPTS || '0' }}"; do
        if ! grep -Fq "$fallback" .github/workflows/deploy-ec2.yml; then
            printf 'deploy workflow missing reconnect fallback: %s\n' "$fallback" >&2
            exit 1
        fi
    done
    printf 'PASS ws_reconnect_defaults enabled=true initial_ms=1000 max_ms=30000 max_attempts=0\n'
}

assert_release_gate_defaults_aligned() {
    rendered="$(service_config_for wsclient-capture --profile recording-capture)"
    if ! printf '%s\n' "$rendered" | grep -q 'published: "8093"'; then
        printf 'recording-capture wsclient-capture default host metrics port is not 8093\n' >&2
        exit 1
    fi
    for fallback in \
        "DEPLOY_DB_PREFLIGHT_REQUIRED: \${{ vars.DEPLOY_DB_PREFLIGHT_REQUIRED || 'false' }}" \
        "WSCLIENT_CAPTURE_METRICS_HOST_PORT: \${{ vars.WSCLIENT_CAPTURE_METRICS_HOST_PORT || '8093' }}"; do
        if ! grep -Fq "$fallback" .github/workflows/deploy-ec2.yml; then
            printf 'deploy workflow missing release-gate fallback: %s\n' "$fallback" >&2
            exit 1
        fi
    done
    for env_line in \
        'DEPLOY_DB_PREFLIGHT_REQUIRED=$DEPLOY_DB_PREFLIGHT_REQUIRED' \
        'WSCLIENT_CAPTURE_METRICS_HOST_PORT=$WSCLIENT_CAPTURE_METRICS_HOST_PORT'; do
        if ! grep -Fq "$env_line" .github/workflows/deploy-ec2.yml; then
            printf 'deploy workflow missing candidate env line: %s\n' "$env_line" >&2
            exit 1
        fi
    done
    if ! grep -Fq 'DEPLOY_DB_PREFLIGHT_REQUIRED="${DEPLOY_DB_PREFLIGHT_REQUIRED:-false}"' scripts/ec2-compose-rollback-gate.sh; then
        printf 'rollback gate DEPLOY_DB_PREFLIGHT_REQUIRED default is not false\n' >&2
        exit 1
    fi
    for expected in \
        '--profile db-primary-product' \
        'DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT="${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT:-8090}"' \
        'db-primary-product)' \
        'featureplant-db-follower "http://127.0.0.1:${FEATUREPLANT_METRICS_HOST_PORT}/health"' \
        'frontend-adapter-db-primary "http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT}/health"' \
        'timescaledb db-migrate featureplant-db-follower frontend-adapter-db-primary' \
        'FEATUREPLANT_DB_URL FRONTEND_ADAPTER_DB_URL DB_WRITER_DATABASE_URL' \
        'DB_PREFLIGHT_DATABASE_URL="$db_url"' \
        'run_db_preflight_cli "$env_file" "$db_url" "$db_user" "$db_password" "$required"' \
        'sudo docker run --rm --network "$network"'; do
        if ! grep -Fq -- "$expected" scripts/ec2-compose-rollback-gate.sh; then
            printf 'rollback gate missing db-primary-product behavior: %s\n' "$expected" >&2
            exit 1
        fi
    done
    printf 'PASS release_gate_defaults db_preflight_required=false wsclient_capture_metrics_host_port=8093\n'
}

assert_featureplant_cursor_config_propagated() {
    rendered="$(service_config_for featureplant --profile featureplant)"
    for expected in \
        'FEATUREPLANT_DB_CURSOR_NAME: ""' \
        'FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED: "false"' \
        'FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY: "250000"' \
        'FEATUREPLANT_DB_OUTPUT_BATCH_SIZE: "500"' \
        'FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS: "5000"' \
        'FEATUREPLANT_METRICS_HOST: 0.0.0.0' \
        'FEATUREPLANT_METRICS_PORT: "0"'; do
        if ! printf '%s\n' "$rendered" | grep -q "^      ${expected}$"; then
            printf 'featureplant service is missing default %s\n' "$expected" >&2
            exit 1
        fi
    done
    for expected in \
        "FEATUREPLANT_DB_CURSOR_NAME: \${{ vars.FEATUREPLANT_DB_CURSOR_NAME }}" \
        'FEATUREPLANT_DB_CURSOR_NAME=$FEATUREPLANT_DB_CURSOR_NAME'; do
        if ! grep -Fq "$expected" .github/workflows/deploy-ec2.yml; then
            printf 'deploy workflow missing featureplant cursor propagation: %s\n' "$expected" >&2
            exit 1
        fi
    done
    printf 'PASS featureplant_cursor_config_propagated\n'
}

assert_live_product_manual_smoke_contract() {
    workflow=".github/workflows/deploy-ec2.yml"
    smoke_script="scripts/live-product-smoke.sh"
    browser_smoke_script="scripts/frontend-product-browser-smoke.sh"
    cdp_browser_smoke_script="scripts/frontend-browser-cdp-smoke.py"
    smoke_probe="src/main/java/edu/illinois/group8/storage/db/LiveProductSmokeDbProbe.java"
    for expected in \
        "deploy_profile:" \
        "run_live_product_smoke:" \
        "run_live_product_browser_smoke:" \
        "require_live_product_data:" \
        "DEPLOY_PROFILE: \${{ github.event_name == 'workflow_dispatch' && inputs.deploy_profile || vars.DEPLOY_PROFILE || 'cluster-live' }}" \
        "RUN_LIVE_PRODUCT_SMOKE: \${{ github.event_name == 'workflow_dispatch' && format('{0}', inputs.run_live_product_smoke) || 'false' }}" \
        "LIVE_PRODUCT_BROWSER_SMOKE_ENABLED: \${{ github.event_name == 'workflow_dispatch' && format('{0}', inputs.run_live_product_browser_smoke) || vars.LIVE_PRODUCT_BROWSER_SMOKE_ENABLED || 'false' }}" \
        "REQUIRE_LIVE_PRODUCT_DATA: \${{ github.event_name == 'workflow_dispatch' && format('{0}', inputs.require_live_product_data) || 'false' }}" \
        "LIVE_PRODUCT_REHEARSAL_ARTIFACT_NAME: live-product-rehearsal-\${{ github.sha }}-\${{ github.run_id }}-\${{ github.run_attempt }}" \
        "LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED: \${{ vars.LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED || 'true' }}" \
        "FRONTEND_ADAPTER_FEATURE_SOURCE: \${{ vars.FRONTEND_ADAPTER_FEATURE_SOURCE || 'latest_market_state' }}" \
        "deployment required configuration missing: %s" \
        "deployment required configuration present: %s" \
        "Validate manual live-product rehearsal inputs" \
        'if [ "$GITHUB_EVENT_NAME" != "workflow_dispatch" ]; then' \
        "DEPLOY_PROFILE must be cluster-live, live-product, or live-product-local-db" \
        "require_live_product_data=true requires deploy_profile=live-product or live-product-local-db" \
        "require_live_product_data=true requires run_live_product_smoke=true" \
        "run_live_product_browser_smoke=true requires run_live_product_smoke=true" \
        "run_live_product_smoke=true requires deploy_profile=live-product or live-product-local-db" \
        "live-product rehearsal required configuration missing: %s" \
        "live-product|live-product-local-db|db-primary-product" \
        "requires FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs or latest_market_state" \
        "LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED=\$LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED" \
        "LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED=\$q_live_product_semantic_smoke_enabled" \
        "env.DEPLOY_PROFILE == 'live-product' || env.DEPLOY_PROFILE == 'live-product-local-db'" \
        "(env.DEPLOY_PROFILE == 'live-product' || env.DEPLOY_PROFILE == 'live-product-local-db') && env.RUN_LIVE_PRODUCT_SMOKE == 'true'" \
        "LIVE_PRODUCT_BROWSER_SMOKE_ENABLED=\$q_live_product_browser_smoke_enabled" \
        "LIVE_PRODUCT_SMOKE_REQUIRE_LIVE_DATA=\$q_require_live_product_data" \
        "COMPOSE_PROFILE=\"\$KALSHI_DEPLOY_PROFILE\"" \
        "FRONTEND_BROWSER_SMOKE_DOCKER_ENABLED=true" \
        "FRONTEND_BROWSER_SMOKE_DOCKER_PREFER=true" \
        "FRONTEND_BROWSER_SMOKE_DOCKER_SUDO=true" \
        "FRONTEND_BROWSER_SMOKE_EVIDENCE_FILE=\"\$browser_evidence\"" \
        "live-product-smoke.stdout.log" \
        "live-product-smoke.stderr.log" \
        "live-product-rehearsal-summary.md" \
        "browser-smoke.json" \
        "browser_dom_sha256" \
        "browser_screenshot_sha256" \
        "Upload live-product rehearsal evidence" \
        "path: live-product-rehearsal/**" \
        "bash -n scripts/live-product-smoke.sh" \
        "sh -n scripts/live-product-smoke.sh" \
        "bash -n scripts/frontend-product-browser-smoke.sh" \
        "sh -n scripts/frontend-product-browser-smoke.sh"; do
        if ! grep -Fq "$expected" "$workflow"; then
            printf 'deploy workflow missing manual live-product smoke contract: %s\n' "$expected" >&2
            exit 1
        fi
    done

    for expected in \
        'WSCLIENT_HEALTH_URL' \
        'STREAM_TAP_HEALTH_URL' \
        'FEATUREPLANT_HEALTH_URL' \
        'FEATUREPLANT_METRICS_URL' \
        'FRONTEND_HEALTH_URL' \
        '/metrics' \
        'wait_featureplant_metrics' \
        'featureplant_db_output_events_total{result="accepted",service="featureplant"}' \
        'featureplant_db_output_events_total{result="written",service="featureplant"}' \
        'featureplant_db_output_queue_depth{service="featureplant"}' \
        'LIVE_PRODUCT_SMOKE_DB_URL' \
        'DB_WRITER_DATABASE_URL' \
        'FEATUREPLANT_DB_URL' \
        'FRONTEND_ADAPTER_DB_URL' \
        'COMPOSE_PROFILE="${COMPOSE_PROFILE:-live-product}"' \
        'live-product-local-db)' \
        'LOCAL_DB_URL="jdbc:postgresql://timescaledb:5432/${LOCAL_DB_NAME}"' \
        'DB_WRITER_DATABASE_URL="$LOCAL_DB_URL"' \
        'DB_WRITER_DATABASE_USER="$LOCAL_DB_USER"' \
        'DB_WRITER_DATABASE_PASSWORD="$LOCAL_DB_PASSWORD"' \
        'LIVE_PRODUCT_SMOKE_DB_URL="$LOCAL_DB_URL"' \
        'LiveProductSmokeDbProbeCli' \
        'cursorCommitSeq' \
        'seedCanonicalEvents' \
        'featureOutputsForPrefix' \
        'latestNonSmokeCanonicalAfter' \
        'featureOutputsForSourceEvent' \
        'latestNonSmokeFeatureOutputAfter' \
        'latencyForSourceEvent' \
        'LIVE_PRODUCT_SMOKE_MAX_E2E_LATENCY_MS="${LIVE_PRODUCT_SMOKE_MAX_E2E_LATENCY_MS:-30000}"' \
        'PASS product_latency' \
        'canonical_to_feature_ms' \
        'seed_to_frontend_quote_ms' \
        'seed_to_sse_ms' \
        'wait_featureplant_cursor_caught_up' \
        'wait_frontend_live_feature_output' \
        'wait_frontend_health_non_smoke_freshness' \
        'wait_frontend_quote_stream' \
        'FEATUREPLANT_DB_CURSOR_NAME' \
        'LIVE_PRODUCT_BROWSER_SMOKE_ENABLED' \
        'check_product_browser_ui' \
        'scripts/frontend-product-browser-smoke.sh' \
        'frontend_static_ui' \
        'vendor/lightweight-charts-4.2.0.standalone.production.js' \
        'frontend static UI must not reference external CDN assets' \
        'unpkg|jsdelivr|cdnjs|cdn' \
        'LIVE_PRODUCT_SMOKE_DOCKER_SUDO="${LIVE_PRODUCT_SMOKE_DOCKER_SUDO:-false}"' \
        'docker_compose()' \
        'sudo docker compose "$@"' \
        'docker compose "$@"' \
        'docker_compose --env-file "$COMPOSE_ENV_FILE" --profile "$COMPOSE_PROFILE" "$@"' \
        'docker_compose --profile "$COMPOSE_PROFILE" "$@"' \
        'LIVE_PRODUCT_SMOKE_REQUIRE_LIVE_DATA'; do
        if ! grep -Fq "$expected" "$smoke_script"; then
            printf 'live-product smoke script missing contract fragment: %s\n' "$expected" >&2
            exit 1
        fi
    done

    for expected in \
        'insert into canonical_events' \
        'featureplant_cursors' \
        'feature_outputs' \
        "event_id not like 'live-product-smoke-%'" \
        'LATEST_NON_SMOKE_CANONICAL_AFTER_SQL' \
        'FEATURE_OUTPUTS_FOR_SOURCE_EVENT_SQL' \
        'LATEST_NON_SMOKE_FEATURE_OUTPUT_AFTER_SQL' \
        'LATENCY_FOR_SOURCE_EVENT_SQL' \
        'latest_market_state' \
        'source_event_id like ?'; do
        if ! grep -Fq "$expected" "$smoke_probe"; then
            printf 'live-product smoke DB probe missing contract fragment: %s\n' "$expected" >&2
            exit 1
        fi
    done

    for expected in \
        'chromium chromium-browser google-chrome google-chrome-stable' \
        'FRONTEND_BROWSER_SMOKE_DOCKER_ENABLED' \
        'FRONTEND_BROWSER_SMOKE_DOCKER_PREFER' \
        'FRONTEND_BROWSER_SMOKE_DOCKER_SUDO' \
        'FRONTEND_BROWSER_SMOKE_DOCKER_IMAGE' \
        'docker_cmd image inspect' \
        'docker_cmd_with_timeout pull' \
        'docker_cmd_with_timeout run --rm' \
        'sudo docker "$@"' \
        'frontend-browser-cdp-smoke.py' \
        'id="chart-container"' \
        '<canvas' \
        'id="quote-update-health"' \
        'quote feed status did not show active SSE/fallback traffic' \
        '(SSE|long-poll) error' \
        'No markets indexed yet' \
        'no feature outputs' \
        'freshness-state' \
        'PASS frontend_browser_smoke'; do
        if ! grep -Fq -- "$expected" "$browser_smoke_script"; then
            printf 'frontend browser smoke script missing contract fragment: %s\n' "$expected" >&2
            exit 1
        fi
    done

    for expected in \
        '--headless=new' \
        '--disable-background-networking' \
        '--disable-component-update' \
        '--no-first-run' \
        'Page.captureScreenshot' \
        'document.documentElement.outerHTML' \
        'INTERACTION_EXPR' \
        'market-search' \
        'market-status-filter' \
        'quoteFeedVisible'; do
        if ! grep -Fq -- "$expected" "$cdp_browser_smoke_script"; then
            printf 'frontend CDP browser smoke script missing contract fragment: %s\n' "$expected" >&2
            exit 1
        fi
    done

    for forbidden in \
        'db-primary-demo-seed.sh' \
        '--force-recreate' \
        'compose up' \
        'compose stop' \
        'compose rm' \
        'docker compose up' \
        'docker compose stop' \
        'docker compose rm' \
        'docker compose run' \
        'psql_scalar()' \
        'compose exec -T -e PGPASSWORD'; do
        if grep -Fq -- "$forbidden" "$smoke_script"; then
            printf 'live-product smoke script must not mutate services: %s\n' "$forbidden" >&2
            exit 1
        fi
    done
    printf 'PASS live_product_manual_smoke_contract\n'
}

assert_latest_market_state_smoke_contract() {
    for script in scripts/db-primary-product-smoke.sh scripts/db-primary-demo-pipeline.sh; do
        for expected in \
            'FRONTEND_ADAPTER_FEATURE_SOURCE="${FRONTEND_ADAPTER_FEATURE_SOURCE:-latest_market_state}"' \
            'FRONTEND_ADAPTER_FEATURE_SOURCE="$FRONTEND_ADAPTER_FEATURE_SOURCE"' \
            'EXPECTED_FEATURE_SOURCE="$FRONTEND_ADAPTER_FEATURE_SOURCE"' \
            'expected_feature_source = sys.argv[2].strip().replace("-", "_")'; do
            if ! grep -Fq "$expected" "$script"; then
                printf '%s missing latest-market-state smoke contract: %s\n' "$script" "$expected" >&2
                exit 1
            fi
        done
    done
    if ! grep -Fq 'EXPECTED_FEATURE_SOURCE="${EXPECTED_FEATURE_SOURCE:-latest_market_state}"' \
        scripts/db-primary-demo-smoke.sh; then
        printf 'db-primary demo smoke missing latest-market-state default expectation\n' >&2
        exit 1
    fi
    for expected in \
        'delete from latest_market_state' \
        'latest_market_state=0 before FeaturePlant'; do
        if ! grep -Fq "$expected" scripts/db-primary-demo-seed.sh scripts/db-primary-demo-seed.sql; then
            printf 'demo seed missing latest-market-state cleanup contract: %s\n' "$expected" >&2
            exit 1
        fi
    done
    for expected in \
        'count_latest_states()' \
        'featureplant latest state check failed' \
        'PASS demo_featureplant feature_outputs_before='; do
        if ! grep -Fq "$expected" scripts/db-primary-demo-run-featureplant.sh; then
            printf 'demo FeaturePlant script missing latest-market-state check: %s\n' "$expected" >&2
            exit 1
        fi
    done
    printf 'PASS latest_market_state_smoke_contract\n'
}

validate_config "cluster-live" --profile cluster-live
validate_config "single-node-local" --profile single-node-local
validate_config "recording-capture" --profile recording-capture
validate_config "observability" --profile observability
validate_config "local-db,frontend-integration" --profile local-db --profile frontend-integration
validate_config "historical-backfill" --profile historical-backfill
validate_config "featureplant" --profile featureplant
validate_config "raw-replay" --profile raw-replay
validate_config "db-primary-product" --profile db-primary-product
validate_config "live-product" --profile live-product
validate_config "live-product-local-db" --profile live-product-local-db

assert_services_absent "observability" --profile observability
assert_services_absent "cluster-live,observability" --profile cluster-live --profile observability
assert_services_absent "live-product" --profile live-product
assert_services_absent "live-product-local-db" --profile live-product-local-db
assert_services_present "recording-capture" --profile recording-capture
assert_db_primary_product_services_present
assert_live_product_services_present
assert_live_product_local_db_services_present
assert_db_primary_product_defaults_aligned
assert_frontend_adapter_db_primary_static_root
assert_frontend_release_health_contract
assert_cluster_live_db_writer_stays_opt_in
assert_live_product_db_writer_expectations
assert_live_product_local_db_writer_expectations
assert_kalshi_app_image_contract
assert_frontend_adapter_metadata_env_present "local-db,frontend-integration" --profile local-db --profile frontend-integration
assert_latest_market_state_smoke_contract
assert_published_ports_loopback "cluster-live" --profile cluster-live
assert_published_ports_loopback "single-node-local" --profile single-node-local
assert_published_ports_loopback "recording-capture" --profile recording-capture
assert_published_ports_loopback "observability" --profile observability
assert_published_ports_loopback "local-db,frontend-integration" --profile local-db --profile frontend-integration
assert_published_ports_loopback "historical-backfill" --profile historical-backfill
assert_published_ports_loopback "featureplant" --profile featureplant
assert_published_ports_loopback "raw-replay" --profile raw-replay
assert_published_ports_loopback "db-primary-product" --profile db-primary-product
assert_published_ports_loopback "live-product" --profile live-product
assert_published_ports_loopback "live-product-local-db" --profile live-product-local-db
assert_no_default_network "cluster-live" --profile cluster-live
assert_no_default_network "recording-capture" --profile recording-capture
assert_no_default_network "observability" --profile observability
assert_no_default_network "local-db,frontend-integration" --profile local-db --profile frontend-integration
assert_no_default_network "historical-backfill" --profile historical-backfill
assert_no_default_network "featureplant" --profile featureplant
assert_no_default_network "raw-replay" --profile raw-replay
assert_no_default_network "db-primary-product" --profile db-primary-product
assert_no_default_network "live-product" --profile live-product
assert_no_default_network "live-product-local-db" --profile live-product-local-db
assert_raw_replay_table_defaults_aligned
assert_ws_reconnect_defaults_aligned
assert_release_gate_defaults_aligned
assert_featureplant_cursor_config_propagated
assert_live_product_manual_smoke_contract
