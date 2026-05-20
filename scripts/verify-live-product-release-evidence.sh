#!/bin/sh
set -eu

summary_mode="false"
case "$#" in
    1)
        evidence_file="$1"
        ;;
    2)
        if [ "$1" != "--summary-md" ]; then
            printf 'Usage: %s [--summary-md] <release-evidence.json>\n' "$0" >&2
            exit 1
        fi
        summary_mode="true"
        evidence_file="$2"
        ;;
    *)
        printf 'Usage: %s [--summary-md] <release-evidence.json>\n' "$0" >&2
        exit 1
        ;;
esac
if [ ! -s "$evidence_file" ]; then
    printf 'release evidence file is missing or empty: %s\n' "$evidence_file" >&2
    exit 1
fi

python3 - "$evidence_file" \
    "${EXPECTED_KALSHI_RELEASE_SHA:-}" \
    "${EXPECTED_KALSHI_GITHUB_RUN_ID:-}" \
    "${EXPECTED_KALSHI_GITHUB_RUN_ATTEMPT:-}" \
    "$summary_mode" \
    "${LIVE_PRODUCT_RELEASE_EVIDENCE_ARTIFACT_NAME:-}" <<'PY'
import json
import sys

path, expected_sha, expected_run_id, expected_run_attempt, summary_mode, artifact_name = sys.argv[1:7]

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
require(isinstance(at(frontend, "feature_source"), str) and at(frontend, "feature_source"), "frontend release health feature_source missing")

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
    require(isinstance(at(frontend_health, "feature_source"), str) and at(frontend_health, "feature_source"), "frontend health feature_source missing")
    require(isinstance(at(frontend_health, "expected_feature_source"), str) and at(frontend_health, "expected_feature_source"), "frontend health expected_feature_source missing")
    require(
        at(frontend_health, "feature_source") == at(frontend_health, "expected_feature_source"),
        "frontend health feature_source mismatch",
    )
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
    require(isinstance(at(final, "feature_source"), str) and at(final, "feature_source"), "final smoke feature_source missing")
    require(isinstance(at(final, "expected_feature_source"), str) and at(final, "expected_feature_source"), "final smoke expected_feature_source missing")
    require(at(final, "feature_source") == at(final, "expected_feature_source"), "final smoke feature_source mismatch")
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

product_latency = at(smoke, "product_latency")
require(isinstance(product_latency, dict), "product_latency snapshot missing")
if isinstance(product_latency, dict):
    require(at(product_latency, "status") == "ok", "product_latency status must be ok")
    for key in (
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
    ):
        require(isinstance(at(product_latency, key), int) and at(product_latency, key) >= 0, f"product_latency {key} must be non-negative integer")
    max_allowed_ms = at(product_latency, "max_allowed_ms")
    if isinstance(max_allowed_ms, int):
        for key in ("seed_to_frontend_quote_ms", "seed_to_sse_ms"):
            value = at(product_latency, key)
            if isinstance(value, int):
                require(value <= max_allowed_ms, f"product_latency {key} exceeds max_allowed_ms")

if errors:
    for error in errors:
        print(f"FAIL live_product_release_evidence {error}", file=sys.stderr)
    raise SystemExit(1)

def md(value):
    if value is None:
        return ""
    text = str(value)
    return text.replace("|", "\\|").replace("\n", " ")

if summary_mode == "true":
    print("### Live Product Release Evidence")
    print()
    print("| Field | Value |")
    print("| --- | --- |")
    rows = (
        ("release_sha", at(evidence, "release_sha")),
        ("github_run_id", at(evidence, "github_run_id")),
        ("github_run_attempt", at(evidence, "github_run_attempt")),
        ("deploy_profile", at(evidence, "deploy_profile")),
        ("outcome", at(evidence, "outcome")),
        ("pipeline_status", at(pipeline, "status") if isinstance(pipeline, dict) else ""),
        ("final_product_readiness", at(final, "product_readiness_status") if isinstance(final, dict) else ""),
        ("frontend_feature_source", at(final, "feature_source") if isinstance(final, dict) else ""),
        ("product_latency_seed_to_quote_ms", at(product_latency, "seed_to_frontend_quote_ms") if isinstance(product_latency, dict) else ""),
        ("product_latency_seed_to_sse_ms", at(product_latency, "seed_to_sse_ms") if isinstance(product_latency, dict) else ""),
        ("frontend_release_sha", at(frontend_health, "release_sha") if isinstance(frontend_health, dict) else ""),
        ("frontend_release_profile", at(frontend_health, "release_profile") if isinstance(frontend_health, dict) else ""),
        ("evidence_artifact", artifact_name),
    )
    for key, value in rows:
        print(f"| {key} | {md(value)} |")
    raise SystemExit(0)

print(
    "PASS live_product_release_evidence "
    f"file={path} "
    f"release_sha={at(evidence, 'release_sha') or ''} "
    f"github_run_id={at(evidence, 'github_run_id') or ''} "
    f"github_run_attempt={at(evidence, 'github_run_attempt') or ''} "
    f"pipeline_status={at(pipeline, 'status') if isinstance(pipeline, dict) else ''} "
    f"final_product_readiness={at(final, 'product_readiness_status') if isinstance(final, dict) else ''} "
    f"feature_source={at(final, 'feature_source') if isinstance(final, dict) else ''} "
    f"seed_to_frontend_quote_ms={at(product_latency, 'seed_to_frontend_quote_ms') if isinstance(product_latency, dict) else ''} "
    f"seed_to_sse_ms={at(product_latency, 'seed_to_sse_ms') if isinstance(product_latency, dict) else ''}"
)
PY
