package edu.illinois.group8.scripts;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbPrimaryDemoScriptsTest {
    @Test
    void demoSeedWritesCanonicalInputsInsteadOfFeatureOutputs() throws Exception {
        String sql = read("scripts/db-primary-demo-seed.sql").toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("insert into canonical_events"));
        assertTrue(sql.contains("'derived.top_of_book'"));
        assertTrue(sql.contains("'canonical.ticker'"));
        assertTrue(sql.contains("'canonical.trade'"));
        assertTrue(sql.contains("demo-db-primary-canonical-bbo"));
        assertFalse(sql.contains("insert into feature_outputs"));
    }

    @Test
    void featurePlantDemoScriptRunsDbToDbAndChecksPersistedOutputs() throws Exception {
        String script = read("scripts/db-primary-demo-run-featureplant.sh");

        assertTrue(script.contains("FEATUREPLANT_SOURCE=db"));
        assertTrue(script.contains("FEATUREPLANT_OUTPUT=db"));
        assertTrue(script.contains("FEATUREPLANT_DB_CURSOR_NAME=\"${FEATUREPLANT_DB_CURSOR_NAME:-}\""));
        assertTrue(script.contains("FEATUREPLANT_DB_CURSOR_NAME=\"$FEATUREPLANT_DB_CURSOR_NAME\""));
        assertTrue(script.contains("FEATUREPLANT_RUN_ONCE=true"));
        assertTrue(script.contains("docker compose --profile featureplant run --rm --build featureplant"));
        assertTrue(script.contains("EXPECTED_FEATURE_OUTPUTS_BEFORE"));
        assertTrue(script.contains("source_event_id like 'demo-db-primary-canonical-%'"));
        assertTrue(script.contains("PASS demo_featureplant"));
    }

    @Test
    void smokeScriptCanAssertRefreshWithoutRestart() throws Exception {
        String script = read("scripts/db-primary-demo-smoke.sh");

        assertTrue(script.contains("EXPECTED_FRONTEND_STARTED_AT"));
        assertTrue(script.contains("EXPECTED_REFRESH_TOTAL_LOADED_MIN"));
        assertTrue(script.contains("EXPECTED_KALSHI_RELEASE_SHA"));
        assertTrue(script.contains("EXPECTED_KALSHI_APP_IMAGE"));
        assertTrue(script.contains("health check failed: release is missing"));
        assertTrue(script.contains("freshness = body.get(\"data_freshness\")"));
        assertTrue(script.contains("latest_event_age_ms"));
        assertTrue(script.contains("feature_output_refresh.running"));
        assertTrue(script.contains("feature_output_refresh.total_loaded"));
        assertProductStaticSmokeContract(script);
    }

    @Test
    void pipelineRecreatesFrontendBeforeFeaturePlantAndRequiresCleanRefresh() throws Exception {
        String script = read("scripts/db-primary-demo-pipeline.sh");

        assertTrue(script.contains("--force-recreate frontend-adapter"));
        assertTrue(script.contains("raw_total_loaded != 0"));
        assertTrue(script.contains("EXPECTED_FEATURE_OUTPUTS_BEFORE=0"));
        assertTrue(script.contains("EXPECTED_FRONTEND_STARTED_AT"));
    }

    @Test
    void productSmokeUsesLongRunningFeaturePlantFollowerAndDurableCursor() throws Exception {
        String script = read("scripts/db-primary-product-smoke.sh");

        assertTrue(script.contains("FEATUREPLANT_DB_CURSOR_NAME=\"${FEATUREPLANT_DB_CURSOR_NAME:-db-primary-product-smoke}\""));
        assertTrue(script.contains("FEATUREPLANT_SOURCE=db"));
        assertTrue(script.contains("FEATUREPLANT_OUTPUT=db"));
        assertTrue(script.contains("FEATUREPLANT_RUN_ONCE=false"));
        assertTrue(script.contains("FEATUREPLANT_DB_INCLUDE_REPLAY=false"));
        assertTrue(script.contains("FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED=\"${FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED:-true}\""));
        assertTrue(script.contains("FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY=\"${FEATUREPLANT_DB_OUTPUT_QUEUE_CAPACITY:-250000}\""));
        assertTrue(script.contains("FEATUREPLANT_DB_OUTPUT_BATCH_SIZE=\"${FEATUREPLANT_DB_OUTPUT_BATCH_SIZE:-500}\""));
        assertTrue(script.contains("FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS=\"${FEATUREPLANT_DB_OUTPUT_CLOSE_TIMEOUT_MS:-5000}\""));
        assertTrue(script.contains("FEATUREPLANT_METRICS_HOST=\"${FEATUREPLANT_METRICS_HOST:-0.0.0.0}\""));
        assertTrue(script.contains("FEATUREPLANT_METRICS_PORT=\"${FEATUREPLANT_METRICS_PORT:-8094}\""));
        assertTrue(script.contains("FEATUREPLANT_METRICS_HOST_PORT=\"${FEATUREPLANT_METRICS_HOST_PORT:-8094}\""));
        assertTrue(script.contains("FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED=\"$FEATUREPLANT_DB_OUTPUT_ASYNC_ENABLED\""));
        assertTrue(script.contains("FEATUREPLANT_METRICS_HOST=\"$FEATUREPLANT_METRICS_HOST\""));
        assertTrue(script.contains("FEATUREPLANT_METRICS_PORT=\"$FEATUREPLANT_METRICS_PORT\""));
        assertTrue(script.contains("FEATUREPLANT_METRICS_HOST_PORT=\"$FEATUREPLANT_METRICS_HOST_PORT\""));
        assertTrue(script.contains("FRONTEND_NO_PROXY=\"${FRONTEND_NO_PROXY:-127.0.0.1,localhost}\""));
        assertTrue(script.contains("curl -fsS --noproxy \"$FRONTEND_NO_PROXY\""));
        assertTrue(script.contains("PASS featureplant_health"));
        assertTrue(script.contains("PASS featureplant_metrics"));
        assertTrue(script.contains("featureplant_db_output_events_total{result=\"accepted\",service=\"featureplant\"}"));
        assertTrue(script.contains("featureplant_db_output_events_total{result=\"written\",service=\"featureplant\"}"));
        assertTrue(script.contains("featureplant_db_output_queue_depth{service=\"featureplant\"}"));
        assertTrue(script.contains("featureplant-db-follower"));
        assertTrue(script.contains("frontend-adapter-db-primary"));
        assertTrue(script.contains("docker compose --profile db-primary-product up -d --build --force-recreate"));
        assertTrue(script.contains("featureplant_cursors"));
        assertTrue(script.contains("last_commit_seq"));
        assertTrue(script.contains("feature_outputs"));
        assertTrue(script.contains("latest_market_state"));
        assertTrue(script.contains("FRONTEND_ADAPTER_FEATURE_SOURCE=\"${FRONTEND_ADAPTER_FEATURE_SOURCE:-latest_market_state}\""));
        assertTrue(script.contains("source_event_id like 'demo-db-primary-canonical-%'"));
        assertTrue(script.contains("EXPECTED_FRONTEND_STARTED_AT"));
        assertTrue(script.contains("EXPECTED_REFRESH_TOTAL_LOADED_MIN=1"));
        assertFalse(script.contains("db-primary-demo-run-featureplant.sh"));
        assertFalse(script.contains("docker compose --profile featureplant run"));
    }

    @Test
    void liveProductSmokeAssumesRunningStackAndChecksFeatureOutputsPath() throws Exception {
        String script = read("scripts/live-product-smoke.sh");
        String probe = read("src/main/java/edu/illinois/group8/storage/db/LiveProductSmokeDbProbe.java");
        String browserSmoke = read("scripts/frontend-product-browser-smoke.sh");

        assertTrue(script.contains("COMPOSE_PROFILE=\"${COMPOSE_PROFILE:-live-product}\""));
        assertTrue(script.contains("LIVE_PRODUCT_SMOKE_DOCKER_SUDO=\"${LIVE_PRODUCT_SMOKE_DOCKER_SUDO:-false}\""));
        assertTrue(script.contains("docker_compose()"));
        assertTrue(script.contains("sudo docker compose \"$@\""));
        assertTrue(script.contains("docker compose \"$@\""));
        assertTrue(script.contains("docker_compose --env-file \"$COMPOSE_ENV_FILE\" --profile \"$COMPOSE_PROFILE\" \"$@\""));
        assertTrue(script.contains("docker_compose --profile \"$COMPOSE_PROFILE\" \"$@\""));
        assertTrue(script.contains("WSCLIENT_HEALTH_URL"));
        assertTrue(script.contains("STREAM_TAP_HEALTH_URL"));
        assertTrue(script.contains("FEATUREPLANT_HEALTH_URL"));
        assertTrue(script.contains("FRONTEND_HEALTH_URL"));
        assertTrue(script.contains("FRONTEND_NO_PROXY=\"${FRONTEND_NO_PROXY:-127.0.0.1,localhost}\""));
        assertTrue(script.contains("curl -fsS --noproxy \"$FRONTEND_NO_PROXY\""));
        assertTrue(script.contains("EXPECTED_KALSHI_RELEASE_SHA"));
        assertTrue(script.contains("EXPECTED_KALSHI_APP_IMAGE"));
        assertTrue(script.contains("EXPECTED_KALSHI_DEPLOY_PROFILE"));
        assertTrue(script.contains("health check failed: release is missing"));
        assertTrue(script.contains("freshness = body.get(\"data_freshness\")"));
        assertTrue(script.contains("readiness = body.get(\"product_readiness\")"));
        assertTrue(script.contains("latest_event_ts_ms"));
        assertTrue(script.contains("latest_event_age_ms"));
        assertProductStaticSmokeContract(script);
        assertTrue(script.contains("LIVE_PRODUCT_SMOKE_DB_URL"));
        assertTrue(script.contains("DB_WRITER_DATABASE_URL"));
        assertTrue(script.contains("FEATUREPLANT_DB_URL"));
        assertTrue(script.contains("FRONTEND_ADAPTER_DB_URL"));
        assertTrue(script.contains("LiveProductSmokeDbProbeCli"));
        assertTrue(script.contains("db_probe_output=\"$tmpdir/db-probe.out\""));
        assertTrue(script.contains("if ! compose run --rm --no-deps -T"));
        assertTrue(script.contains("tr -d '\\r' < \"$db_probe_output\""));
        assertTrue(script.contains("cursorCommitSeq"));
        assertTrue(script.contains("seedCanonicalEvents"));
        assertTrue(script.contains("featureOutputsForPrefix"));
        assertTrue(script.contains("latestNonSmokeCanonicalAfter"));
        assertTrue(script.contains("featureOutputsForSourceEvent"));
        assertTrue(script.contains("latestNonSmokeFeatureOutputAfter"));
        assertTrue(script.contains("pipelineReliabilitySnapshot"));
        assertTrue(script.contains("PASS pipeline_reliability"));
        assertTrue(script.contains("wait_featureplant_cursor_caught_up"));
        assertTrue(script.contains("wait_frontend_live_feature_output"));
        assertTrue(script.contains("wait_frontend_health_non_smoke_freshness"));
        assertTrue(script.contains("LIVE_PRODUCT_BROWSER_SMOKE_ENABLED"));
        assertTrue(script.contains("check_product_browser_ui"));
        assertTrue(probe.contains("insert into canonical_events"));
        assertTrue(probe.contains("on conflict do nothing"));
        assertTrue(probe.contains("featureplant_cursors"));
        assertTrue(probe.contains("feature_outputs"));
        assertTrue(probe.contains("source_event_id like ?"));
        assertTrue(probe.contains("LATEST_NON_SMOKE_CANONICAL_AFTER_SQL"));
        assertTrue(probe.contains("FEATURE_OUTPUTS_FOR_SOURCE_EVENT_SQL"));
        assertTrue(probe.contains("LATEST_NON_SMOKE_FEATURE_OUTPUT_AFTER_SQL"));
        assertTrue(script.contains("if [ \"$cursor_before\" -gt \"$max_commit_before\" ]; then"));
        assertTrue(script.contains("check_pipeline_reliability_snapshot"));
        assertTrue(script.contains("if [ \"$seeded_count\" -ne 3 ] || [ \"$target_commit_seq\" -le \"$cursor_before\" ]; then"));
        assertTrue(script.contains("wait_featureplant_followed_seed \"$seed_prefix\" \"$target_commit_seq\""));
        assertTrue(script.contains("wait_featureplant_metrics \"$feature_outputs_after\""));
        assertTrue(script.contains("wait_frontend_refresh_progress \"$frontend_started_at_before\" \"$frontend_loaded_before\" \"$frontend_errors_before\""));
        assertTrue(script.contains("wait_frontend_feature_output \"$market_ticker\" \"$bbo_event_id\""));
        assertTrue(script.contains("wait_frontend_quote \"$market_ticker\""));
        assertTrue(script.contains("wait_frontend_quote_stream \"$market_ticker\" 461500"));
        assertTrue(script.contains("check_optional_live_data \"$max_commit_before\""));
        assertTrue(script.contains("product_readiness_status"));
        assertTrue(
            script.indexOf("check_optional_live_data \"$max_commit_before\"")
                < script.indexOf("seed_result=\"$(seed_canonical_events"),
            "strict live data gate must run before synthetic smoke seed so global /health freshness is not masked"
        );
        assertTrue(probe.contains("event_id not like 'live-product-smoke-%'"));
        assertTrue(script.contains("/features?symbol=${encoded_market}&feature=${encoded_feature}&limit=20"));
        assertTrue(script.contains("/quotes?symbols=${encoded_market}"));
        assertTrue(script.contains("LIVE_PRODUCT_SMOKE_REQUIRE_LIVE_DATA"));
        assertTrue(script.contains("live data requirement failed: no feature-eligible non-smoke canonical_events after baseline_commit_seq"));
        assertTrue(script.contains("PASS live_product_smoke"));
        assertTrue(browserSmoke.contains("--headless=new"));
        assertTrue(browserSmoke.contains("--disable-background-networking"));
        assertTrue(browserSmoke.contains("--disable-component-update"));
        assertTrue(browserSmoke.contains("--no-first-run"));
        assertTrue(browserSmoke.contains("--dump-dom"));
        assertTrue(browserSmoke.contains("id=\"chart-container\""));
        assertTrue(browserSmoke.contains("<canvas"));
        assertTrue(browserSmoke.contains("id=\"quote-update-health\""));
        assertTrue(browserSmoke.contains("quote feed status did not show active SSE/fallback traffic"));
        assertTrue(browserSmoke.contains("(SSE|long-poll) error"));
        assertTrue(browserSmoke.contains("No markets indexed yet"));
        assertTrue(browserSmoke.contains("no feature outputs"));
        assertTrue(browserSmoke.contains("freshness-state"));
        assertTrue(browserSmoke.contains("PASS frontend_browser_smoke"));
        assertFalse(script.contains("db-primary-demo-seed.sh"));
        assertFalse(script.contains("--force-recreate"));
        assertFalse(script.contains("compose up"));
        assertFalse(script.contains("compose stop"));
        assertFalse(script.contains("compose rm"));
        assertFalse(script.contains("docker compose up"));
        assertFalse(script.contains("docker compose stop"));
        assertFalse(script.contains("docker compose rm"));
        assertFalse(script.contains("docker compose run"));
        assertFalse(script.contains("psql_scalar()"));
        assertFalse(script.contains("compose exec -T -e PGPASSWORD"));
        assertFalse(script.contains("LOCAL_DB_NAME"));
        assertFalse(script.contains("timescaledb"));
    }

    @Test
    void rollbackGateTreatsDbPrimaryProductAsFirstClassProfile() throws Exception {
        String script = read("scripts/ec2-compose-rollback-gate.sh");

        assertTrue(script.contains("--profile local-db"));
        assertTrue(script.contains("--profile db-primary-product"));
        assertTrue(script.contains("DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT=\"${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT:-8090}\""));
        assertTrue(script.contains("FEATUREPLANT_METRICS_HOST_PORT=\"${FEATUREPLANT_METRICS_HOST_PORT:-8094}\""));
        assertTrue(script.contains("timescaledb db-migrate featureplant-db-follower frontend-adapter-db-primary"));
        assertTrue(script.contains("db-primary-product)"));
        assertTrue(script.contains("featureplant-db-follower \"http://127.0.0.1:${FEATUREPLANT_METRICS_HOST_PORT}/health\""));
        assertTrue(script.contains("frontend-adapter-db-primary \"http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT}/health\""));
        assertTrue(script.contains("db-primary-product) printf '%s\\n' featureplant-db-follower"));
        assertTrue(script.contains("FEATUREPLANT_DB_URL FRONTEND_ADAPTER_DB_URL DB_WRITER_DATABASE_URL"));
        assertTrue(script.contains("DB_PREFLIGHT_DATABASE_URL=\"$db_url\""));
    }

    @Test
    void rollbackGateTreatsLiveProductAsFirstClassProfile() throws Exception {
        String script = read("scripts/ec2-compose-rollback-gate.sh");

        assertTrue(script.contains("--profile live-product"));
        assertTrue(script.contains("node0 node1 node2 wsclient streamtap"));
        assertTrue(script.contains("live-product) printf '%s\\n' wsclient"));
        assertTrue(script.contains("validate_live_product_db_writer()"));
        assertTrue(script.contains("validate_live_product_frontend_feature_source()"));
        assertTrue(script.contains("LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED=\"${LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED:-true}\""));
        assertTrue(script.contains("FRONTEND_NO_PROXY=\"${FRONTEND_NO_PROXY:-127.0.0.1,localhost}\""));
        assertTrue(script.contains("live-product requires DB_WRITER_ENABLED=true."));
        assertTrue(script.contains("live-product requires DB_WRITER_DATABASE_URL, DB_WRITER_DATABASE_USER, and DB_WRITER_DATABASE_PASSWORD."));
        assertTrue(script.contains("live-product requires DB writer, FeaturePlant, and frontend DB URLs to match."));
        assertTrue(script.contains("live-product requires DB writer, FeaturePlant, and frontend DB users to match."));
        assertTrue(script.contains("live-product requires DB writer, FeaturePlant, and frontend DB passwords to match."));
        assertTrue(script.contains("live-product requires FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs or latest_market_state"));
        assertTrue(script.contains("Running live-product Flyway migration against DB_WRITER_DATABASE_URL before release preflight."));
        assertTrue(script.contains("compose_profile \"$env_file\" run --rm --no-deps -T db-migrate-live"));
        assertTrue(script.contains("curl -fsS --noproxy \"$FRONTEND_NO_PROXY\""));
        assertTrue(script.contains("run_live_product_semantic_smoke \"$env_file\""));
        assertTrue(script.contains("live-product semantic smoke must be enabled before recording a live-product deploy success."));
        assertTrue(script.contains("LIVE_PRODUCT_SMOKE_JSON"));
        assertTrue(script.contains("live_product_smoke_summary_json()"));
        assertTrue(script.contains("> \"$smoke_stdout\" 2> \"$smoke_stderr\""));
        assertTrue(script.contains("LIVE_PRODUCT_SMOKE_DOCKER_SUDO=true"));
        assertTrue(script.contains("sh scripts/live-product-smoke.sh"));
        assertTrue(script.contains("wsclient \"http://127.0.0.1:${WSCLIENT_METRICS_HOST_PORT}/health\""));
        assertTrue(script.contains("streamtap \"http://127.0.0.1:${STREAM_TAP_HOST_PORT}/health\""));
        assertTrue(script.contains("featureplant-db-follower \"http://127.0.0.1:${FEATUREPLANT_METRICS_HOST_PORT}/health\""));
        assertTrue(script.contains("frontend-adapter-db-primary \"http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT}/health\""));
        assertTrue(script.contains("required=\"true\""));
        assertTrue(script.contains("log \"Building Docker Compose services for DEPLOY_PROFILE=$DEPLOY_PROFILE before stopping current services.\""));
        assertTrue(script.contains("if ! compose_profile \"$env_file\" build; then"));
        assertTrue(script.contains("compose_app_image()"));
        assertTrue(script.contains("env_file_value \"$env_file\" KALSHI_APP_IMAGE"));
        assertTrue(script.contains("compose_app_image_tar()"));
        assertTrue(script.contains("env_file_value \"$env_file\" KALSHI_APP_IMAGE_TAR"));
        assertTrue(script.contains("CANDIDATE_IMAGE_TAR=\"${CANDIDATE_IMAGE_TAR:-}\""));
        assertTrue(script.contains("build_or_verify_app_image()"));
        assertTrue(script.contains("sudo docker image inspect \"$app_image\""));
        assertTrue(script.contains("load_app_image_from_tar \"$app_image\" \"$image_tar\""));
        assertTrue(script.contains("skipping Docker Compose build"));
        assertTrue(script.contains("if ! build_or_verify_app_image \"$env_file\"; then"));
        assertTrue(script.contains("if ! run_db_release_preflight \"$env_file\"; then"));
        assertTrue(script.contains("log \"Stopping existing Compose services for controlled deploy.\""));
        assertTrue(script.indexOf("if ! build_or_verify_app_image \"$env_file\"; then")
            < script.indexOf("if ! run_db_release_preflight \"$env_file\"; then"));
        assertTrue(script.indexOf("if ! run_db_release_preflight \"$env_file\"; then")
            < script.indexOf("log \"Stopping existing Compose services for controlled deploy.\""));
        assertTrue(script.contains("if ! profile_health_smoke; then"));
        assertTrue(script.contains("profile_app_services()"));
        assertTrue(script.contains("assert_runtime_container_images()"));
        assertTrue(script.contains("sudo docker inspect -f '{{.Config.Image}}'"));
        assertTrue(script.contains("expected_image=\"$app_image\""));
        assertTrue(script.contains("container image mismatch"));
        assertTrue(script.contains("if ! assert_runtime_container_images \"$env_file\"; then"));
        assertTrue(script.indexOf("if ! compose_profile \"$env_file\" up -d --no-build --remove-orphans; then")
            < script.indexOf("if ! assert_runtime_container_images \"$env_file\"; then"));
        assertTrue(script.indexOf("if ! assert_runtime_container_images \"$env_file\"; then")
            < script.indexOf("if ! profile_health_smoke; then"));
        assertTrue(script.indexOf("if ! run_live_product_semantic_smoke \"$env_file\"; then")
            < script.indexOf("record_success"));
        assertTrue(script.contains("last_success.image"));
        assertTrue(script.contains("last_success.image_tar"));
        assertTrue(script.contains("success_ref=\"$(git rev-parse HEAD)\""));
        assertTrue(script.contains("tmp_prefix=\"$DEPLOY_STATE_DIR/.last_success.$$\""));
        assertTrue(script.contains("printf '%s\\n' \"$app_image\" > \"$tmp_image\""));
        assertTrue(script.contains("printf '%s\\n' \"$image_tar\" > \"$tmp_image_tar\""));
        assertTrue(script.contains("chmod 600 \"$tmp_ref\" \"$tmp_env\" \"$tmp_profile\""));
        assertTrue(script.contains("mv \"$tmp_ref\" \"$DEPLOY_STATE_DIR/last_success.ref\""));
        assertTrue(script.contains("mv \"$tmp_env\" \"$DEPLOY_STATE_DIR/last_success.env\""));
        assertTrue(script.contains("mv \"$tmp_profile\" \"$DEPLOY_STATE_DIR/last_success.profile\""));
        assertTrue(script.contains("mv \"$tmp_image\" \"$DEPLOY_STATE_DIR/last_success.image\""));
        assertTrue(script.contains("mv \"$tmp_image_tar\" \"$DEPLOY_STATE_DIR/last_success.image_tar\""));
        assertTrue(script.indexOf("if [ -z \"$image_tar\" ] || [ ! -s \"$image_tar\" ]; then")
            < script.indexOf("tmp_prefix=\"$DEPLOY_STATE_DIR/.last_success.$$\""));
        assertTrue(script.contains("previous_image_file=\"$DEPLOY_STATE_DIR/last_success.image\""));
        assertTrue(script.contains("previous_image_tar_file=\"$DEPLOY_STATE_DIR/last_success.image_tar\""));
        assertTrue(script.contains("load_last_success_image_if_needed()"));
        assertTrue(script.contains("load_app_image_from_tar \"$previous_image\" \"$previous_image_tar\""));
        assertFalse(script.contains("Skipping health smoke checks for DEPLOY_PROFILE=live-product"));
    }

    @Test
    void rollbackGateWritesMachineReadableReleaseEvidence() throws Exception {
        String script = read("scripts/ec2-compose-rollback-gate.sh");
        String validator = read("scripts/validate-release-evidence.sh");

        assertTrue(script.contains("RELEASE_EVIDENCE_DIR=\"$DEPLOY_STATE_DIR/releases\""));
        assertTrue(script.contains("release_evidence_file()"));
        assertTrue(script.contains("release_evidence_json()"));
        assertTrue(script.contains("write_release_evidence()"));
        assertTrue(script.contains("tmp_file=\"$target.tmp.$$\""));
        assertTrue(script.contains("mv \"$tmp_file\" \"$target\""));
        assertTrue(script.contains("\"release_sha\""));
        assertTrue(script.contains("\"github_run_id\""));
        assertTrue(script.contains("\"github_run_attempt\""));
        assertTrue(script.contains("\"deploy_profile\""));
        assertTrue(script.contains("\"candidate_ref\""));
        assertTrue(script.contains("\"app_image\""));
        assertTrue(script.contains("\"app_image_id\""));
        assertTrue(script.contains("\"candidate_image_tar\""));
        assertTrue(script.contains("\"env_file_sha256\""));
        assertTrue(script.contains("\"runtime_images\""));
        assertTrue(script.contains("\"db_preflight\""));
        assertTrue(script.contains("\"profile_health_smoke\""));
        assertTrue(script.contains("\"live_product_semantic_smoke\""));
        assertTrue(script.contains("\"live_product_smoke\""));
        assertTrue(script.contains("\"frontend_release_health\""));
        assertTrue(script.contains("\"rollback\""));
        assertTrue(script.contains("\"outcome\""));
        assertTrue(script.contains("write_release_evidence \"candidate\" \"candidate_gates_passed\""));
        assertTrue(script.contains("write_release_evidence \"candidate\" \"success\""));
        assertTrue(script.contains("write_release_evidence \"candidate\" \"candidate_failed_rollback_pending\""));
        assertTrue(script.contains("write_release_evidence \"candidate\" \"record_success_failed_rollback_pending\""));
        assertTrue(script.contains("POST_GATE_FAILURE_CLASS=\"release_evidence_write_failed\""));
        assertTrue(script.contains("write_release_evidence \"rollback\" \"release_evidence_failed_rollback_succeeded\""));
        assertTrue(script.contains("write_release_evidence \"rollback\" \"release_evidence_failed_rollback_failed\""));
        assertTrue(script.contains("write_release_evidence \"rollback\" \"candidate_failed_rollback_succeeded\""));
        assertTrue(script.contains("write_release_evidence \"rollback\" \"candidate_failed_rollback_failed\""));
        assertTrue(script.contains("write_release_evidence \"rollback\" \"record_success_failed_rollback_succeeded\""));
        assertTrue(script.contains("write_release_evidence \"rollback\" \"record_success_failed_rollback_failed\""));
        assertTrue(script.indexOf("write_release_evidence \"candidate\" \"candidate_gates_passed\"")
            < script.indexOf("elif record_success; then"));
        assertFalse(script.contains("release evidence recording failed; leaving deploy in failed state."));
        assertTrue(script.indexOf("write_release_evidence \"candidate\" \"record_success_failed_rollback_pending\"")
            < script.indexOf("log \"Candidate deploy succeeded but last-success state recording failed; attempting rollback if possible.\""));
        assertTrue(script.contains("ROLLBACK_TARGET_REF=\"$previous_ref\""));
        assertTrue(script.contains("ROLLBACK_TARGET_PROFILE=\"$DEPLOY_PROFILE\""));
        assertTrue(script.contains("ROLLBACK_TARGET_IMAGE=\"$(sed -n '1p' \"$DEPLOY_STATE_DIR/last_success.image\""));
        assertTrue(script.contains("ROLLBACK_TARGET_IMAGE_TAR=\"$(sed -n '1p' \"$DEPLOY_STATE_DIR/last_success.image_tar\""));
        assertTrue(validator.contains("PASS release_evidence_contract"));
        assertTrue(validator.contains("sed '/^if \\[ ! -f \"\\$CANDIDATE_ENV_FILE\" \\]; then$/,$d' \"$rollback_gate\""));
        assertTrue(validator.contains("write_release_evidence \"candidate\" \"success\""));
        assertTrue(validator.contains("scripts/verify-live-product-release-evidence.sh \"$evidence_file\""));
        assertTrue(validator.contains("degraded-release-evidence.json"));
        assertTrue(validator.contains("verifier accepted degraded product readiness"));
        assertTrue(validator.contains("release evidence summary missing expected field"));
        assertTrue(validator.contains("release evidence summary leaked forbidden value"));
        assertTrue(validator.contains("| evidence_artifact | live-product-release-evidence-release-sha-123-2 |"));
        assertTrue(validator.contains("python3 -m json.tool \"$evidence_file\""));
        assertTrue(validator.contains("secret-db-password"));
        String verifier = read("scripts/verify-live-product-release-evidence.sh");
        assertTrue(verifier.contains("PASS live_product_release_evidence"));
        assertTrue(verifier.contains("final product_readiness must not be degraded"));
        assertTrue(verifier.contains("final product_readiness status must be ok"));

        String evidenceBlock = script.substring(
            script.indexOf("release_evidence_json()"),
            script.indexOf("write_release_evidence()"));
        for (String forbidden : new String[] {
            "DB_WRITER_DATABASE_PASSWORD",
            "FEATUREPLANT_DB_PASSWORD",
            "FRONTEND_ADAPTER_DB_PASSWORD",
            "RAW_REPLAY_DATABASE_PASSWORD",
            "LOCAL_DB_PASSWORD",
            "KALSHI_PRIVATE_KEY",
            "KALSHI_KEY_ID",
            "KALSHI_KEY_PATH"
        }) {
            assertFalse(evidenceBlock.contains(forbidden), "release evidence must not serialize " + forbidden);
        }
    }

    @Test
    void composeValidatorPinsLiveProductProfileAndDbWriterContract() throws Exception {
        String script = read("scripts/validate-compose-profiles.sh");

        assertTrue(script.contains("validate_config \"live-product\" --profile live-product"));
        assertTrue(script.contains("assert_services_absent \"live-product\" --profile live-product"));
        assertTrue(script.contains("assert_live_product_services_present"));
        assertTrue(script.contains("node0 node1 node2 wsclient db-migrate-live streamtap featureplant-db-follower frontend-adapter-db-primary"));
        assertTrue(script.contains("assert_cluster_live_db_writer_stays_opt_in"));
        assertTrue(script.contains("'DB_WRITER_ENABLED: \"\"'"));
        assertTrue(script.contains("\"LOCAL_DB_PASSWORD: \\${{ secrets.LOCAL_DB_PASSWORD || secrets.DB_WRITER_DATABASE_PASSWORD || 'kalshi' }}\""));
        assertTrue(script.contains("\"FRONTEND_ADAPTER_DB_PASSWORD: \\${{ secrets.FRONTEND_ADAPTER_DB_PASSWORD || secrets.DB_WRITER_DATABASE_PASSWORD }}\""));
        assertTrue(script.contains("'LOCAL_DB_PASSWORD=$LOCAL_DB_PASSWORD'"));
        assertTrue(script.contains("'FRONTEND_ADAPTER_DB_PASSWORD=$FRONTEND_ADAPTER_DB_PASSWORD'"));
        assertTrue(script.contains("assert_live_product_db_writer_expectations"));
        assertTrue(script.contains("live_db_url=\"jdbc:postgresql://live-db.example.internal:5432/kalshi_live\""));
        assertTrue(script.contains("FLYWAY_URL: $live_db_url"));
        assertTrue(script.contains("live-product must not include local DB service"));
        assertTrue(script.contains("assert_published_ports_loopback \"live-product\" --profile live-product"));
        assertTrue(script.contains("assert_no_default_network \"live-product\" --profile live-product"));
        assertTrue(script.contains("assert_live_product_manual_smoke_contract"));
        assertTrue(script.contains("assert_kalshi_app_image_contract"));
        assertTrue(script.contains("assert_frontend_release_health_contract"));
        assertTrue(script.contains("run_live_product_browser_smoke"));
        assertTrue(script.contains("LIVE_PRODUCT_BROWSER_SMOKE_ENABLED"));
        assertTrue(script.contains("KALSHI_RELEASE_SHA"));
        assertTrue(script.contains("KALSHI_DEPLOY_PROFILE"));
        assertTrue(script.contains("KALSHI_GITHUB_RUN_ID"));
        assertTrue(script.contains("EXPECTED_KALSHI_RELEASE_SHA"));
        assertTrue(script.contains("rendered_default_image=\"$(KALSHI_APP_IMAGE= service_config_for \"$service\" $profile_args)\""));
        assertTrue(script.contains("data_freshness"));
        assertTrue(script.contains("release-identity"));
        assertTrue(script.contains("health-data-age"));
        assertTrue(script.contains("app_image=\"example/kalshi:aj\""));
        assertTrue(script.contains("KALSHI_APP_IMAGE=\"$app_image\""));
        assertTrue(script.contains("^    image: $app_image$"));
        assertTrue(script.contains("s3-recording-sync must not use KALSHI_APP_IMAGE"));
        assertTrue(script.contains("actions/upload-artifact@v6"));
        assertTrue(script.contains("actions/download-artifact@v7"));
        assertTrue(script.contains("KALSHI_APP_IMAGE_TAR: .deploy-state/images/kalshi-project-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}.tar.gz"));
        assertTrue(script.contains("sudo docker image inspect \"$app_image\""));
        assertTrue(script.contains("skipping Docker Compose build"));
        assertTrue(script.contains("assert_runtime_container_images()"));
        assertTrue(script.contains("last_success.image"));
        assertTrue(script.contains("last_success.image_tar"));
        assertTrue(script.contains("FRONTEND_ADAPTER_FEATURE_SOURCE: \\${{ vars.FRONTEND_ADAPTER_FEATURE_SOURCE || 'feature_outputs' }}"));
        assertTrue(script.contains("'FRONTEND_ADAPTER_FEATURE_SOURCE=$FRONTEND_ADAPTER_FEATURE_SOURCE'"));
        assertTrue(script.contains("LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED=\"${LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED:-true}\""));
        assertTrue(script.contains("validate_live_product_frontend_feature_source()"));
        assertTrue(script.contains("run_live_product_semantic_smoke \"$env_file\""));
        assertTrue(script.contains("scripts/live-product-smoke.sh"));
        assertTrue(script.contains("'vendor/lightweight-charts-4.2.0.standalone.production.js'"));
        assertTrue(script.contains("'id=\"quote-update-health\"'"));
        assertTrue(script.contains("'frontend static UI must not reference external CDN assets'"));
        assertTrue(script.contains("'unpkg|jsdelivr|cdnjs|cdn'"));
    }

    @Test
    void tradingViewFrontendUsesOnlyLocalStaticAssets() throws Exception {
        String index = read("frontend/tradingview-lightweight/index.html");
        String app = read("frontend/tradingview-lightweight/app.js");
        String styles = read("frontend/tradingview-lightweight/styles.css");
        String chart = read("frontend/tradingview-lightweight/vendor/lightweight-charts-4.2.0.standalone.production.js");

        assertTrue(index.contains("<link rel=\"stylesheet\" href=\"styles.css\" />"));
        assertTrue(index.contains("<script src=\"vendor/lightweight-charts-4.2.0.standalone.production.js\"></script>"));
        assertTrue(index.contains("<script src=\"app.js\"></script>"));
        assertTrue(index.contains("id=\"release-identity\""));
        assertTrue(index.contains("id=\"health-data-age\""));
        assertTrue(index.contains("id=\"quote-update-health\""));
        assertTrue(app.contains("body.release"));
        assertTrue(app.contains("body.data_freshness"));
        assertTrue(app.contains("body.quote_streams"));
        assertTrue(app.contains("body.quote_updates"));
        assertTrue(app.contains("EventSource"));
        assertTrue(app.contains("/quotes/stream?symbols="));
        assertTrue(app.contains("long-poll timeout"));
        assertTrue(app.contains("long-poll fallback"));
        assertTrue(app.contains("fallback polling"));
        assertTrue(app.contains("latest_event_ts_ms"));
        assertTrue(chart.contains("TradingView Lightweight Charts"));
        assertTrue(chart.contains("v4.2.0"));
        assertNoExternalCdn(index);
        assertNoExternalCdn(app);
        assertNoExternalCdn(styles);
        assertNoExternalCdn(chart);
    }

    @Test
    void deployWorkflowPropagatesDbPrimaryProductPortsToRollbackGate() throws Exception {
        String workflow = read(".github/workflows/deploy-ec2.yml");

        assertTrue(workflow.contains("DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT: ${{ vars.DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT || '8090' }}"));
        assertTrue(workflow.contains("KALSHI_RELEASE_SHA: ${{ github.sha }}"));
        assertTrue(workflow.contains("KALSHI_DEPLOY_PROFILE: ${{ github.event_name == 'workflow_dispatch' && inputs.deploy_profile || vars.DEPLOY_PROFILE || 'cluster-live' }}"));
        assertTrue(workflow.contains("KALSHI_GITHUB_RUN_ID: ${{ github.run_id }}"));
        assertTrue(workflow.contains("KALSHI_GITHUB_RUN_ATTEMPT: ${{ github.run_attempt }}"));
        assertTrue(workflow.contains("FEATUREPLANT_METRICS_HOST_PORT: ${{ vars.FEATUREPLANT_METRICS_HOST_PORT || '8094' }}"));
        assertTrue(workflow.contains("LOCAL_DB_NAME: ${{ vars.LOCAL_DB_NAME || 'kalshi_test' }}"));
        assertTrue(workflow.contains("LOCAL_DB_USER: ${{ vars.LOCAL_DB_USER || 'kalshi' }}"));
        assertTrue(workflow.contains("LOCAL_DB_PASSWORD: ${{ secrets.LOCAL_DB_PASSWORD || secrets.DB_WRITER_DATABASE_PASSWORD || 'kalshi' }}"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_URL: ${{ vars.FRONTEND_ADAPTER_DB_URL || vars.DB_WRITER_DATABASE_URL }}"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_USER: ${{ vars.FRONTEND_ADAPTER_DB_USER || vars.DB_WRITER_DATABASE_USER }}"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_PASSWORD: ${{ secrets.FRONTEND_ADAPTER_DB_PASSWORD || secrets.DB_WRITER_DATABASE_PASSWORD }}"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_FEATURE_SOURCE: ${{ vars.FRONTEND_ADAPTER_FEATURE_SOURCE || 'feature_outputs' }}"));
        assertTrue(workflow.contains("DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT=$DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT"));
        assertTrue(workflow.contains("FEATUREPLANT_METRICS_HOST_PORT=$FEATUREPLANT_METRICS_HOST_PORT"));
        assertTrue(workflow.contains("LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED=$LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED"));
        assertTrue(workflow.contains("KALSHI_RELEASE_SHA=$KALSHI_RELEASE_SHA"));
        assertTrue(workflow.contains("KALSHI_DEPLOY_PROFILE=$KALSHI_DEPLOY_PROFILE"));
        assertTrue(workflow.contains("KALSHI_GITHUB_RUN_ID=$KALSHI_GITHUB_RUN_ID"));
        assertTrue(workflow.contains("KALSHI_GITHUB_RUN_ATTEMPT=$KALSHI_GITHUB_RUN_ATTEMPT"));
        assertTrue(workflow.contains("LIVE_PRODUCT_RELEASE_EVIDENCE_ARTIFACT_NAME: live-product-release-evidence-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}"));
        assertTrue(workflow.contains("LOCAL_DB_NAME=$LOCAL_DB_NAME"));
        assertTrue(workflow.contains("LOCAL_DB_USER=$LOCAL_DB_USER"));
        assertTrue(workflow.contains("LOCAL_DB_PASSWORD=$LOCAL_DB_PASSWORD"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_URL=$FRONTEND_ADAPTER_DB_URL"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_USER=$FRONTEND_ADAPTER_DB_USER"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_PASSWORD=$FRONTEND_ADAPTER_DB_PASSWORD"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_FEATURE_SOURCE=$FRONTEND_ADAPTER_FEATURE_SOURCE"));
        assertTrue(workflow.contains("live-product|db-primary-product)"));
        assertTrue(workflow.contains("requires FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs or latest_market_state"));
        assertTrue(workflow.contains("printf -v q_db_primary_product_frontend_host_port '%q' \"$DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT\""));
        assertTrue(workflow.contains("printf -v q_featureplant_metrics_host_port '%q' \"$FEATUREPLANT_METRICS_HOST_PORT\""));
        assertTrue(workflow.contains("printf -v q_live_product_semantic_smoke_enabled '%q' \"$LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED\""));
        assertTrue(workflow.contains("printf -v q_kalshi_release_sha '%q' \"$KALSHI_RELEASE_SHA\""));
        assertTrue(workflow.contains("printf -v q_kalshi_github_run_id '%q' \"$KALSHI_GITHUB_RUN_ID\""));
        assertTrue(workflow.contains("printf -v q_kalshi_github_run_attempt '%q' \"$KALSHI_GITHUB_RUN_ATTEMPT\""));
        assertTrue(workflow.contains("KALSHI_RELEASE_SHA=$q_kalshi_release_sha"));
        assertTrue(workflow.contains("KALSHI_GITHUB_RUN_ID=$q_kalshi_github_run_id"));
        assertTrue(workflow.contains("KALSHI_GITHUB_RUN_ATTEMPT=$q_kalshi_github_run_attempt"));
        assertTrue(workflow.contains("EXPECTED_KALSHI_RELEASE_SHA=$q_kalshi_release_sha"));
        assertTrue(workflow.contains("EXPECTED_KALSHI_APP_IMAGE=$q_kalshi_app_image"));
        assertTrue(workflow.contains("EXPECTED_KALSHI_DEPLOY_PROFILE=$q_deploy_profile"));
        assertTrue(workflow.contains("sudo dnf install -y python3"));
        assertTrue(workflow.contains("DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT=$q_db_primary_product_frontend_host_port"));
        assertTrue(workflow.contains("FEATUREPLANT_METRICS_HOST_PORT=$q_featureplant_metrics_host_port"));
        assertTrue(workflow.contains("LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED=$q_live_product_semantic_smoke_enabled"));
        assertTrue(workflow.contains("bash -n scripts/validate-release-evidence.sh"));
        assertTrue(workflow.contains("sh -n scripts/validate-release-evidence.sh"));
        assertTrue(workflow.contains("bash -n scripts/verify-live-product-release-evidence.sh"));
        assertTrue(workflow.contains("sh -n scripts/verify-live-product-release-evidence.sh"));
        assertTrue(workflow.contains("run: scripts/validate-release-evidence.sh"));
        assertTrue(workflow.contains("Verify live-product release evidence"));
        assertTrue(workflow.contains("if: env.DEPLOY_PROFILE == 'live-product'"));
        assertTrue(workflow.contains("scripts/verify-live-product-release-evidence.sh $q_evidence_file"));
        assertTrue(workflow.contains("printf -v q_remote_evidence_path '%q' \"$DEPLOY_PATH/$evidence_file\""));
        assertTrue(workflow.contains("scp -i ~/.ssh/ec2_key \"$EC2_USER@$EC2_HOST:$q_remote_evidence_path\" \"$local_evidence\""));
        assertTrue(workflow.contains("scripts/verify-live-product-release-evidence.sh --summary-md \"$local_evidence\" >> \"$GITHUB_STEP_SUMMARY\""));
        assertTrue(workflow.contains("Upload live-product release evidence"));
        assertTrue(workflow.contains("name: ${{ env.LIVE_PRODUCT_RELEASE_EVIDENCE_ARTIFACT_NAME }}"));
        assertTrue(workflow.contains("path: release-evidence/*.json"));
        assertTrue(workflow.contains("retention-days: 30"));
        assertTrue(workflow.contains("EXPECTED_KALSHI_GITHUB_RUN_ID=$q_kalshi_github_run_id"));
        assertTrue(workflow.contains("EXPECTED_KALSHI_GITHUB_RUN_ATTEMPT=$q_kalshi_github_run_attempt"));
    }

    @Test
    void deployWorkflowShipsImmutableCiBuiltAppImage() throws Exception {
        String workflow = read(".github/workflows/deploy-ec2.yml");
        String compose = read("docker-compose.yml");

        assertTrue(workflow.contains("KALSHI_APP_IMAGE: kalshi-project:${{ github.sha }}"));
        assertTrue(workflow.contains("docker build --pull --build-arg MAVEN_PACKAGE_ARGS=\"-DskipTests package\" -t \"$KALSHI_APP_IMAGE\" ."));
        assertTrue(workflow.contains("docker image inspect \"$KALSHI_APP_IMAGE\""));
        assertTrue(workflow.contains("docker save \"$KALSHI_APP_IMAGE\" | gzip -1"));
        assertTrue(workflow.contains("uses: actions/upload-artifact@v6"));
        assertTrue(workflow.contains("uses: actions/download-artifact@v7"));
        assertTrue(workflow.contains("kalshi-project-image-${{ github.sha }}"));
        assertTrue(workflow.contains("KALSHI_APP_IMAGE_TAR: .deploy-state/images/kalshi-project-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}.tar.gz"));
        assertTrue(workflow.contains("mkdir -p '$DEPLOY_PATH/.deploy-state/images'"));
        assertTrue(workflow.contains("scp -i ~/.ssh/ec2_key \"$image_tar\" \"$EC2_USER@$EC2_HOST:$DEPLOY_PATH/$KALSHI_APP_IMAGE_TAR\""));
        assertTrue(workflow.contains("IMAGE_TAR=\"\\$APP_DIR/$KALSHI_APP_IMAGE_TAR\""));
        assertTrue(workflow.contains("LAST_SUCCESS_ENV=\"\\$STATE_DIR/last_success.env\""));
        assertTrue(workflow.contains("previous_image=\"\\$(sed -n 's/^KALSHI_APP_IMAGE=//p' \"\\$LAST_SUCCESS_ENV\" | tail -n 1)\""));
        assertTrue(workflow.contains("sudo docker save \"\\$previous_image\" | gzip -1 > \"\\$previous_tar\""));
        assertTrue(workflow.contains("printf '%s\\n' \"\\$previous_tar_rel\" > \"\\$LAST_SUCCESS_IMAGE_TAR\""));
        assertTrue(workflow.contains("gzip -t \"\\$IMAGE_TAR\""));
        assertTrue(workflow.contains("sha256sum \"\\$IMAGE_TAR\" > \"\\$IMAGE_TAR.sha256\""));
        assertTrue(workflow.contains("gzip -dc \"\\$IMAGE_TAR\" | sudo docker load"));
        assertTrue(workflow.contains("sudo docker image inspect \"$KALSHI_APP_IMAGE\" >/dev/null"));
        assertFalse(workflow.contains("rm -f \"\\$IMAGE_TAR\""));
        assertTrue(workflow.contains("KALSHI_APP_IMAGE=$KALSHI_APP_IMAGE"));
        assertTrue(workflow.contains("KALSHI_APP_IMAGE_TAR=$KALSHI_APP_IMAGE_TAR"));
        assertTrue(workflow.contains("printf -v q_kalshi_app_image '%q' \"$KALSHI_APP_IMAGE\""));
        assertTrue(workflow.contains("printf -v q_candidate_image_tar '%q' \"$KALSHI_APP_IMAGE_TAR\""));
        assertTrue(workflow.contains("KALSHI_APP_IMAGE=$q_kalshi_app_image"));
        assertTrue(workflow.contains("CANDIDATE_IMAGE_TAR=$q_candidate_image_tar"));

        for (String service : new String[] {
            "node0", "node0-capture", "node1", "node2", "wsclient", "wsclient-capture",
            "raw-ingress-replay", "historical-backfill", "streamtap", "stream-recorder",
            "featureplant", "featureplant-db-follower", "frontend-adapter-db-primary",
            "frontend-adapter"
        }) {
            assertTrue(
                compose.contains("  " + service + ":\n    build: .\n    image: ${KALSHI_APP_IMAGE:-kalshi-project:local}"),
                "missing KALSHI_APP_IMAGE contract for " + service);
        }
        assertTrue(compose.contains("  s3-recording-sync:\n    build:\n      context: .\n      dockerfile: ops/docker/s3-recording-sync.Dockerfile\n    image: group_08_project-s3-recording-sync"));
    }

    @Test
    void deployWorkflowSupportsManualLiveProductSmoke() throws Exception {
        String workflow = read(".github/workflows/deploy-ec2.yml");

        assertTrue(workflow.contains("deploy_profile:"));
        assertTrue(workflow.contains("default: cluster-live"));
        assertTrue(workflow.contains("- live-product"));
        assertTrue(workflow.contains("run_live_product_smoke:"));
        assertTrue(workflow.contains("run_live_product_browser_smoke:"));
        assertTrue(workflow.contains("type: boolean"));
        assertTrue(workflow.contains("DEPLOY_PROFILE: ${{ github.event_name == 'workflow_dispatch' && inputs.deploy_profile || vars.DEPLOY_PROFILE || 'cluster-live' }}"));
        assertTrue(workflow.contains("RUN_LIVE_PRODUCT_SMOKE: ${{ github.event_name == 'workflow_dispatch' && inputs.run_live_product_smoke || false }}"));
        assertTrue(workflow.contains("LIVE_PRODUCT_BROWSER_SMOKE_ENABLED: ${{ github.event_name == 'workflow_dispatch' && inputs.run_live_product_browser_smoke || vars.LIVE_PRODUCT_BROWSER_SMOKE_ENABLED || 'false' }}"));
        assertTrue(workflow.contains("LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED: ${{ vars.LIVE_PRODUCT_SEMANTIC_SMOKE_ENABLED || 'true' }}"));
        assertTrue(workflow.contains("bash -n scripts/live-product-smoke.sh"));
        assertTrue(workflow.contains("sh -n scripts/live-product-smoke.sh"));
        assertTrue(workflow.contains("bash -n scripts/verify-live-product-release-evidence.sh"));
        assertTrue(workflow.contains("sh -n scripts/verify-live-product-release-evidence.sh"));
        assertTrue(workflow.contains("bash -n scripts/frontend-product-browser-smoke.sh"));
        assertTrue(workflow.contains("sh -n scripts/frontend-product-browser-smoke.sh"));
        assertTrue(workflow.contains("if: env.DEPLOY_PROFILE == 'live-product' && env.RUN_LIVE_PRODUCT_SMOKE == 'true'"));
        assertTrue(workflow.contains("LIVE_PRODUCT_BROWSER_SMOKE_ENABLED=$q_live_product_browser_smoke_enabled"));
        assertTrue(workflow.contains("LIVE_PRODUCT_SMOKE_DOCKER_SUDO=true sh scripts/live-product-smoke.sh"));
    }

    private static String read(String path) throws Exception {
        Path file = Path.of(path);
        Assumptions.assumeTrue(
                Files.exists(file),
                "script fixture not present in this build context: " + path);
        return Files.readString(file);
    }

    private static void assertProductStaticSmokeContract(String script) {
        assertTrue(script.contains("chart_file=\"$tmpdir/frontend-lightweight-charts.js\""));
        assertTrue(script.contains("${FRONTEND_BASE_URL}/vendor/lightweight-charts-4.2.0.standalone.production.js"));
        assertTrue(script.contains("<link rel=\"stylesheet\" href=\"styles.css\" />"));
        assertTrue(script.contains("<script src=\"vendor/lightweight-charts-4.2.0.standalone.production.js\"></script>"));
        assertTrue(script.contains("<script src=\"app.js\"></script>"));
        assertTrue(script.contains("release-identity"));
        assertTrue(script.contains("health-data-age"));
        assertTrue(script.contains("quote-update-health"));
        assertTrue(script.contains("/quotes/stream"));
        assertTrue(script.contains("curl -fsS -N --max-time 3"));
        assertTrue(script.contains("/quotes/stream?symbols=${encoded"));
        assertTrue(script.contains("SSE data event"));
        assertTrue(script.contains("server_ts_ms"));
        assertTrue(script.contains("changed"));
        assertTrue(script.contains("quotes_stream"));
        assertTrue(script.contains("EventSource"));
        assertTrue(script.contains("body.quote_streams"));
        assertTrue(script.contains("body.release"));
        assertTrue(script.contains("body.data_freshness"));
        assertTrue(script.contains("latest_event_ts_ms"));
        assertTrue(script.contains("LightweightCharts"));
        assertTrue(script.contains("frontend static UI must not reference external CDN assets"));
        assertTrue(script.contains("unpkg|jsdelivr|cdnjs|cdn"));
    }

    private static void assertNoExternalCdn(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("https://unpkg.com"));
        assertFalse(lower.contains("http://unpkg.com"));
        assertFalse(lower.contains("cdn.jsdelivr"));
        assertFalse(lower.contains("jsdelivr"));
        assertFalse(lower.contains("cdnjs"));
    }
}
