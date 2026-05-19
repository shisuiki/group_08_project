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
        assertTrue(script.contains("feature_output_refresh.running"));
        assertTrue(script.contains("feature_output_refresh.total_loaded"));
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
        assertTrue(script.contains("source_event_id like 'demo-db-primary-canonical-%'"));
        assertTrue(script.contains("EXPECTED_FRONTEND_STARTED_AT"));
        assertTrue(script.contains("EXPECTED_REFRESH_TOTAL_LOADED_MIN=1"));
        assertFalse(script.contains("db-primary-demo-run-featureplant.sh"));
        assertFalse(script.contains("docker compose --profile featureplant run"));
    }

    @Test
    void liveProductSmokeAssumesRunningStackAndChecksFeatureOutputsPath() throws Exception {
        String script = read("scripts/live-product-smoke.sh");

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
        assertTrue(script.contains("insert into canonical_events"));
        assertTrue(script.contains("on conflict do nothing"));
        assertTrue(script.contains("featureplant_cursors"));
        assertTrue(script.contains("feature_outputs"));
        assertTrue(script.contains("source_event_id like"));
        assertTrue(script.contains("/features?symbol=${encoded_market}&feature=${encoded_feature}&limit=20"));
        assertTrue(script.contains("/quotes?symbols=${encoded_market}"));
        assertTrue(script.contains("LIVE_PRODUCT_SMOKE_REQUIRE_LIVE_DATA"));
        assertTrue(script.contains("PASS live_product_smoke"));
        assertFalse(script.contains("db-primary-demo-seed.sh"));
        assertFalse(script.contains("--force-recreate"));
        assertFalse(script.contains("compose up"));
        assertFalse(script.contains("compose stop"));
        assertFalse(script.contains("compose rm"));
        assertFalse(script.contains("compose run"));
        assertFalse(script.contains("docker compose up"));
        assertFalse(script.contains("docker compose stop"));
        assertFalse(script.contains("docker compose rm"));
        assertFalse(script.contains("docker compose run"));
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
        assertTrue(script.contains("timescaledb db-migrate node0 node1 node2 wsclient streamtap"));
        assertTrue(script.contains("live-product) printf '%s\\n' wsclient"));
        assertTrue(script.contains("validate_live_product_db_writer()"));
        assertTrue(script.contains("live-product requires DB_WRITER_ENABLED=true."));
        assertTrue(script.contains("live-product requires DB_WRITER_DATABASE_URL, DB_WRITER_DATABASE_USER, and DB_WRITER_DATABASE_PASSWORD."));
        assertTrue(script.contains("live-product requires DB writer, FeaturePlant, and frontend DB URLs to match."));
        assertTrue(script.contains("live-product requires DB writer, FeaturePlant, and frontend DB users to match."));
        assertTrue(script.contains("live-product requires DB writer, FeaturePlant, and frontend DB passwords to match."));
        assertTrue(script.contains("Skipping DB release preflight: live-product uses managed local Timescale; db-migrate validates after startup."));
        assertTrue(script.contains("wsclient \"http://127.0.0.1:${WSCLIENT_METRICS_HOST_PORT}/health\""));
        assertTrue(script.contains("streamtap \"http://127.0.0.1:${STREAM_TAP_HOST_PORT}/health\""));
        assertTrue(script.contains("featureplant-db-follower \"http://127.0.0.1:${FEATUREPLANT_METRICS_HOST_PORT}/health\""));
        assertTrue(script.contains("frontend-adapter-db-primary \"http://127.0.0.1:${DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT}/health\""));
        assertTrue(script.contains("required=\"true\""));
        assertFalse(script.contains("Skipping health smoke checks for DEPLOY_PROFILE=live-product"));
    }

    @Test
    void composeValidatorPinsLiveProductProfileAndDbWriterContract() throws Exception {
        String script = read("scripts/validate-compose-profiles.sh");

        assertTrue(script.contains("validate_config \"live-product\" --profile live-product"));
        assertTrue(script.contains("assert_services_absent \"live-product\" --profile live-product"));
        assertTrue(script.contains("assert_live_product_services_present"));
        assertTrue(script.contains("node0 node1 node2 wsclient timescaledb db-migrate streamtap featureplant-db-follower frontend-adapter-db-primary"));
        assertTrue(script.contains("assert_cluster_live_db_writer_stays_opt_in"));
        assertTrue(script.contains("'DB_WRITER_ENABLED: \"\"'"));
        assertTrue(script.contains("\"LOCAL_DB_PASSWORD: \\${{ secrets.LOCAL_DB_PASSWORD || secrets.DB_WRITER_DATABASE_PASSWORD || 'kalshi' }}\""));
        assertTrue(script.contains("\"FRONTEND_ADAPTER_DB_PASSWORD: \\${{ secrets.FRONTEND_ADAPTER_DB_PASSWORD || secrets.DB_WRITER_DATABASE_PASSWORD }}\""));
        assertTrue(script.contains("'LOCAL_DB_PASSWORD=$LOCAL_DB_PASSWORD'"));
        assertTrue(script.contains("'FRONTEND_ADAPTER_DB_PASSWORD=$FRONTEND_ADAPTER_DB_PASSWORD'"));
        assertTrue(script.contains("assert_live_product_db_writer_expectations"));
        assertTrue(script.contains("DB_WRITER_DATABASE_URL=jdbc:postgresql://timescaledb:5432/kalshi_test"));
        assertTrue(script.contains("assert_published_ports_loopback \"live-product\" --profile live-product"));
        assertTrue(script.contains("assert_no_default_network \"live-product\" --profile live-product"));
        assertTrue(script.contains("assert_live_product_manual_smoke_contract"));
        assertTrue(script.contains("scripts/live-product-smoke.sh"));
    }

    @Test
    void deployWorkflowPropagatesDbPrimaryProductPortsToRollbackGate() throws Exception {
        String workflow = read(".github/workflows/deploy-ec2.yml");

        assertTrue(workflow.contains("DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT: ${{ vars.DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT || '8090' }}"));
        assertTrue(workflow.contains("FEATUREPLANT_METRICS_HOST_PORT: ${{ vars.FEATUREPLANT_METRICS_HOST_PORT || '8094' }}"));
        assertTrue(workflow.contains("LOCAL_DB_NAME: ${{ vars.LOCAL_DB_NAME || 'kalshi_test' }}"));
        assertTrue(workflow.contains("LOCAL_DB_USER: ${{ vars.LOCAL_DB_USER || 'kalshi' }}"));
        assertTrue(workflow.contains("LOCAL_DB_PASSWORD: ${{ secrets.LOCAL_DB_PASSWORD || secrets.DB_WRITER_DATABASE_PASSWORD || 'kalshi' }}"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_URL: ${{ vars.FRONTEND_ADAPTER_DB_URL || vars.DB_WRITER_DATABASE_URL }}"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_USER: ${{ vars.FRONTEND_ADAPTER_DB_USER || vars.DB_WRITER_DATABASE_USER }}"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_PASSWORD: ${{ secrets.FRONTEND_ADAPTER_DB_PASSWORD || secrets.DB_WRITER_DATABASE_PASSWORD }}"));
        assertTrue(workflow.contains("DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT=$DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT"));
        assertTrue(workflow.contains("FEATUREPLANT_METRICS_HOST_PORT=$FEATUREPLANT_METRICS_HOST_PORT"));
        assertTrue(workflow.contains("LOCAL_DB_NAME=$LOCAL_DB_NAME"));
        assertTrue(workflow.contains("LOCAL_DB_USER=$LOCAL_DB_USER"));
        assertTrue(workflow.contains("LOCAL_DB_PASSWORD=$LOCAL_DB_PASSWORD"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_URL=$FRONTEND_ADAPTER_DB_URL"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_USER=$FRONTEND_ADAPTER_DB_USER"));
        assertTrue(workflow.contains("FRONTEND_ADAPTER_DB_PASSWORD=$FRONTEND_ADAPTER_DB_PASSWORD"));
        assertTrue(workflow.contains("printf -v q_db_primary_product_frontend_host_port '%q' \"$DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT\""));
        assertTrue(workflow.contains("printf -v q_featureplant_metrics_host_port '%q' \"$FEATUREPLANT_METRICS_HOST_PORT\""));
        assertTrue(workflow.contains("DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT=$q_db_primary_product_frontend_host_port"));
        assertTrue(workflow.contains("FEATUREPLANT_METRICS_HOST_PORT=$q_featureplant_metrics_host_port"));
    }

    @Test
    void deployWorkflowSupportsManualLiveProductSmoke() throws Exception {
        String workflow = read(".github/workflows/deploy-ec2.yml");

        assertTrue(workflow.contains("deploy_profile:"));
        assertTrue(workflow.contains("default: cluster-live"));
        assertTrue(workflow.contains("- live-product"));
        assertTrue(workflow.contains("run_live_product_smoke:"));
        assertTrue(workflow.contains("type: boolean"));
        assertTrue(workflow.contains("DEPLOY_PROFILE: ${{ github.event_name == 'workflow_dispatch' && inputs.deploy_profile || vars.DEPLOY_PROFILE || 'cluster-live' }}"));
        assertTrue(workflow.contains("RUN_LIVE_PRODUCT_SMOKE: ${{ github.event_name == 'workflow_dispatch' && inputs.run_live_product_smoke || false }}"));
        assertTrue(workflow.contains("bash -n scripts/live-product-smoke.sh"));
        assertTrue(workflow.contains("sh -n scripts/live-product-smoke.sh"));
        assertTrue(workflow.contains("if: env.DEPLOY_PROFILE == 'live-product' && env.RUN_LIVE_PRODUCT_SMOKE == 'true'"));
        assertTrue(workflow.contains("LIVE_PRODUCT_SMOKE_DOCKER_SUDO=true sh scripts/live-product-smoke.sh"));
    }

    private static String read(String path) throws Exception {
        Path file = Path.of(path);
        Assumptions.assumeTrue(
                Files.exists(file),
                "script fixture not present in this build context: " + path);
        return Files.readString(file);
    }
}
