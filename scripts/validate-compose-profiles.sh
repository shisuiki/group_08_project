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
assert_published_ports_loopback "cluster-live" --profile cluster-live
assert_published_ports_loopback "single-node-local" --profile single-node-local
assert_published_ports_loopback "recording-capture" --profile recording-capture
assert_published_ports_loopback "observability" --profile observability
assert_published_ports_loopback "local-db,frontend-integration" --profile local-db --profile frontend-integration
assert_published_ports_loopback "historical-backfill" --profile historical-backfill
assert_published_ports_loopback "featureplant" --profile featureplant
assert_published_ports_loopback "raw-replay" --profile raw-replay
assert_raw_replay_table_defaults_aligned
