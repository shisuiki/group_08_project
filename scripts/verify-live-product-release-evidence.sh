#!/bin/sh
set -eu

if [ $# -ne 1 ]; then
    printf 'Usage: %s <release-evidence.json>\n' "$0" >&2
    exit 1
fi

evidence_file="$1"
if [ ! -s "$evidence_file" ]; then
    printf 'release evidence file is missing or empty: %s\n' "$evidence_file" >&2
    exit 1
fi

python3 - "$evidence_file" \
    "${EXPECTED_KALSHI_RELEASE_SHA:-}" \
    "${EXPECTED_KALSHI_GITHUB_RUN_ID:-}" \
    "${EXPECTED_KALSHI_GITHUB_RUN_ATTEMPT:-}" <<'PY'
import json
import sys

path, expected_sha, expected_run_id, expected_run_attempt = sys.argv[1:5]

with open(path, "r", encoding="utf-8") as handle:
    evidence = json.load(handle)

errors = []

def require(condition, message):
    if not condition:
        errors.append(message)

def at(mapping, key):
    return mapping.get(key) if isinstance(mapping, dict) else None

require(at(evidence, "schema_version") == 1, "schema_version must be 1")
require(at(evidence, "evidence_type") == "candidate", "evidence_type must be candidate")
require(at(evidence, "outcome") == "success", "outcome must be success")
require(at(evidence, "deploy_profile") == "live-product", "deploy_profile must be live-product")
if expected_sha:
    require(at(evidence, "release_sha") == expected_sha, "release_sha mismatch")
if expected_run_id:
    require(str(at(evidence, "github_run_id")) == expected_run_id, "github_run_id mismatch")
if expected_run_attempt:
    require(str(at(evidence, "github_run_attempt")) == expected_run_attempt, "github_run_attempt mismatch")

gates = at(evidence, "gates")
require(at(gates, "live_product_semantic_smoke") == "passed", "live_product_semantic_smoke gate must pass")

frontend = at(evidence, "frontend_release_health")
require(at(frontend, "status") == "observed", "frontend release health must be observed")

smoke = at(evidence, "live_product_smoke")
require(at(smoke, "checked") is True, "live_product_smoke.checked must be true")
require(at(smoke, "status") == "passed", "live_product_smoke.status must be passed")
require(at(smoke, "final_pass") is True, "final PASS live_product_smoke must be present")
output_sha = at(smoke, "output_sha256")
require(isinstance(output_sha, str) and len(output_sha) == 64, "live_product_smoke output_sha256 missing")

pipeline = at(smoke, "pipeline_reliability")
require(isinstance(pipeline, dict), "pipeline reliability snapshot missing")
if isinstance(pipeline, dict):
    require(at(pipeline, "status") in {"ok", "stale", "degraded"}, "pipeline reliability status invalid")
    for key in (
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
    ):
        require(isinstance(at(pipeline, key), int) and at(pipeline, key) >= 0, f"pipeline {key} must be non-negative integer")

frontend_health = at(smoke, "frontend_health")
require(isinstance(frontend_health, dict), "frontend PASS health missing")
if isinstance(frontend_health, dict):
    require(at(frontend_health, "service") == "frontend-adapter", "frontend health service mismatch")
    require(at(frontend_health, "release_profile") == "live-product", "frontend release_profile mismatch")
    if expected_sha:
        require(at(frontend_health, "release_sha") == expected_sha, "frontend release_sha mismatch")
    for key in (
        "feature_output_refresh_total_loaded",
        "refresh_errors",
        "freshness_event_ts_ms",
        "freshness_age_ms",
    ):
        value = at(frontend_health, key)
        require(value == "" or isinstance(value, int), f"frontend health {key} must be integer or empty")
    require("product_readiness_status" in frontend_health, "frontend product_readiness_status missing")

final = at(smoke, "live_product_smoke")
require(isinstance(final, dict), "final live_product_smoke fields missing")
if isinstance(final, dict):
    for key in (
        "cursor_before",
        "target_commit_seq",
        "cursor_after",
        "feature_outputs",
        "frontend_total_loaded_before",
        "frontend_total_loaded_after",
        "frontend_refresh_errors_after",
    ):
        require(isinstance(at(final, key), int) and at(final, key) >= 0, f"final smoke {key} must be non-negative integer")
    require(at(final, "product_readiness_stale") is False, "final product_readiness must not be stale")
    require(at(final, "product_readiness_degraded") is False, "final product_readiness must not be degraded")
    require(at(final, "product_readiness_status") == "ok", "final product_readiness status must be ok")

if errors:
    for error in errors:
        print(f"FAIL live_product_release_evidence {error}", file=sys.stderr)
    raise SystemExit(1)

print(
    "PASS live_product_release_evidence "
    f"file={path} "
    f"release_sha={at(evidence, 'release_sha') or ''} "
    f"github_run_id={at(evidence, 'github_run_id') or ''} "
    f"github_run_attempt={at(evidence, 'github_run_attempt') or ''} "
    f"pipeline_status={at(pipeline, 'status') if isinstance(pipeline, dict) else ''} "
    f"final_product_readiness={at(final, 'product_readiness_status') if isinstance(final, dict) else ''}"
)
PY
