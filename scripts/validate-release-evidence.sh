#!/bin/sh
set -eu

rollback_gate="scripts/ec2-compose-rollback-gate.sh"
workflow=".github/workflows/deploy-ec2.yml"

require_contains() {
    file="$1"
    expected="$2"
    if ! grep -Fq "$expected" "$file"; then
        printf '%s missing release evidence contract: %s\n' "$file" "$expected" >&2
        exit 1
    fi
}

for expected in \
    'RELEASE_EVIDENCE_DIR="$DEPLOY_STATE_DIR/releases"' \
    'release_evidence_file()' \
    'release_evidence_json()' \
    'write_release_evidence()' \
    'tmp_file="$target.tmp.$$"' \
    'mv "$tmp_file" "$target"' \
    '"release_sha"' \
    '"github_run_id"' \
    '"github_run_attempt"' \
    '"deploy_profile"' \
    '"candidate_ref"' \
    '"app_image"' \
    '"app_image_id"' \
    '"candidate_image_tar"' \
    '"env_file_sha256"' \
    '"db_preflight"' \
    '"profile_health_smoke"' \
    '"live_product_semantic_smoke"' \
    '"frontend_release_health"' \
    '"runtime_images"' \
    '"rollback"' \
    '"outcome"' \
    'write_release_evidence "candidate" "candidate_gates_passed"' \
    'write_release_evidence "candidate" "success"' \
    'write_release_evidence "candidate" "candidate_failed_rollback_pending"' \
    'write_release_evidence "candidate" "record_success_failed_rollback_pending"' \
    'write_release_evidence "rollback" "release_evidence_failed_rollback_succeeded"' \
    'write_release_evidence "rollback" "release_evidence_failed_rollback_failed"' \
    'write_release_evidence "rollback" "candidate_failed_rollback_succeeded"' \
    'write_release_evidence "rollback" "candidate_failed_rollback_failed"' \
    'write_release_evidence "rollback" "record_success_failed_rollback_succeeded"' \
    'write_release_evidence "rollback" "record_success_failed_rollback_failed"'; do
    require_contains "$rollback_gate" "$expected"
done

evidence_block="$(
    sed -n '/release_evidence_json()/,/write_release_evidence()/p' "$rollback_gate"
)"
for forbidden in \
    'DB_WRITER_DATABASE_PASSWORD' \
    'FEATUREPLANT_DB_PASSWORD' \
    'FRONTEND_ADAPTER_DB_PASSWORD' \
    'RAW_REPLAY_DATABASE_PASSWORD' \
    'LOCAL_DB_PASSWORD' \
    'KALSHI_PRIVATE_KEY' \
    'KALSHI_KEY_ID' \
    'KALSHI_KEY_PATH'; do
    if printf '%s\n' "$evidence_block" | grep -Fq "$forbidden"; then
        printf 'release evidence block must not serialize secret key %s\n' "$forbidden" >&2
        exit 1
    fi
done

require_contains "$workflow" 'bash -n scripts/validate-release-evidence.sh'
require_contains "$workflow" 'sh -n scripts/validate-release-evidence.sh'
require_contains "$workflow" 'scripts/validate-release-evidence.sh'
require_contains "$workflow" 'KALSHI_RELEASE_SHA=$q_kalshi_release_sha'
require_contains "$workflow" 'KALSHI_GITHUB_RUN_ID=$q_kalshi_github_run_id'
require_contains "$workflow" 'KALSHI_GITHUB_RUN_ATTEMPT=$q_kalshi_github_run_attempt'

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/release-evidence-contract.XXXXXX")"
cleanup() {
    rm -rf "$tmpdir"
}
trap cleanup EXIT HUP INT TERM

function_file="$tmpdir/rollback-gate-functions.sh"
sed '/^if \[ ! -f "\$CANDIDATE_ENV_FILE" \]; then$/,$d' "$rollback_gate" > "$function_file"

candidate_env="$tmpdir/.env.next"
compose_env="$tmpdir/.env"
state_dir="$tmpdir/state"
cat > "$candidate_env" <<'EOF'
KALSHI_RELEASE_SHA=release-sha
KALSHI_GITHUB_RUN_ID=123
KALSHI_GITHUB_RUN_ATTEMPT=2
KALSHI_DEPLOY_PROFILE=live-product
KALSHI_APP_IMAGE=
DB_WRITER_DATABASE_PASSWORD=secret-db-password
FEATUREPLANT_DB_PASSWORD=secret-featureplant-password
FRONTEND_ADAPTER_DB_PASSWORD=secret-frontend-password
LOCAL_DB_PASSWORD=secret-local-password
KALSHI_PRIVATE_KEY=secret-private-key
KALSHI_KEY_ID=secret-key-id
KALSHI_KEY_PATH=/tmp/secret-key-path
EOF
cp "$candidate_env" "$compose_env"

(
    DEPLOY_PROFILE=live-product
    CANDIDATE_ENV_FILE="$candidate_env"
    COMPOSE_ENV_FILE="$compose_env"
    DEPLOY_STATE_DIR="$state_dir"
    . "$function_file"

    candidate_ref="candidate-ref"
    COMPOSE_CONFIG_STATUS="passed"
    APP_IMAGE_STATUS="passed"
    DB_PREFLIGHT_STATUS="passed"
    COMPOSE_DOWN_STATUS="passed"
    COMPOSE_UP_STATUS="passed"
    RUNTIME_IMAGE_STATUS="passed"
    PROFILE_HEALTH_SMOKE_STATUS="passed"
    LIVE_PRODUCT_SEMANTIC_SMOKE_STATUS="passed"
    RECORD_SUCCESS_STATUS="passed"
    FRONTEND_RELEASE_HEALTH_JSON='{"checked":true,"status":"observed"}'
    write_release_evidence "candidate" "success" >/dev/null
)

evidence_file="$state_dir/releases/release-sha-123-2.json"
if [ ! -s "$evidence_file" ]; then
    printf 'release evidence validator did not create expected JSON file: %s\n' "$evidence_file" >&2
    exit 1
fi
python3 -m json.tool "$evidence_file" >/dev/null
for forbidden_value in \
    secret-db-password \
    secret-featureplant-password \
    secret-frontend-password \
    secret-local-password \
    secret-private-key \
    secret-key-id \
    secret-key-path; do
    if grep -Fq "$forbidden_value" "$evidence_file"; then
        printf 'release evidence JSON leaked forbidden value %s\n' "$forbidden_value" >&2
        exit 1
    fi
done

printf 'PASS release_evidence_contract\n'
