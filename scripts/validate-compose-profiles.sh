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

assert_frontend_adapter_metadata_env_present() {
    label="$1"
    shift
    rendered="$(service_config_for frontend-adapter "$@")"
    for env_name in FRONTEND_ADAPTER_METADATA_SOURCE FRONTEND_ADAPTER_METADATA_MAX_ROWS; do
        if ! printf '%s\n' "$rendered" | grep -q "^      ${env_name}:"; then
            printf 'profile %s frontend-adapter is missing environment %s\n' "$label" "$env_name" >&2
            exit 1
        fi
    done
    printf 'PASS compose_service_environment profiles=%s service=frontend-adapter env=FRONTEND_ADAPTER_METADATA_SOURCE,FRONTEND_ADAPTER_METADATA_MAX_ROWS\n' "$label"
}

validate_config "cluster-live" --profile cluster-live
validate_config "single-node-local" --profile single-node-local
validate_config "recording-capture" --profile recording-capture
validate_config "observability" --profile observability
validate_config "local-db,frontend-integration" --profile local-db --profile frontend-integration
validate_config "historical-backfill" --profile historical-backfill
validate_config "featureplant" --profile featureplant
validate_config "raw-replay" --profile raw-replay

assert_services_absent "observability" --profile observability
assert_services_absent "cluster-live,observability" --profile cluster-live --profile observability
assert_services_present "recording-capture" --profile recording-capture
assert_frontend_adapter_metadata_env_present "local-db,frontend-integration" --profile local-db --profile frontend-integration
