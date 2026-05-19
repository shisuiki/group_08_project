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
    void deployWorkflowPropagatesDbPrimaryProductPortsToRollbackGate() throws Exception {
        String workflow = read(".github/workflows/deploy-ec2.yml");

        assertTrue(workflow.contains("DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT: ${{ vars.DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT || '8090' }}"));
        assertTrue(workflow.contains("FEATUREPLANT_METRICS_HOST_PORT: ${{ vars.FEATUREPLANT_METRICS_HOST_PORT || '8094' }}"));
        assertTrue(workflow.contains("DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT=$DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT"));
        assertTrue(workflow.contains("FEATUREPLANT_METRICS_HOST_PORT=$FEATUREPLANT_METRICS_HOST_PORT"));
        assertTrue(workflow.contains("printf -v q_db_primary_product_frontend_host_port '%q' \"$DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT\""));
        assertTrue(workflow.contains("printf -v q_featureplant_metrics_host_port '%q' \"$FEATUREPLANT_METRICS_HOST_PORT\""));
        assertTrue(workflow.contains("DB_PRIMARY_PRODUCT_FRONTEND_HOST_PORT=$q_db_primary_product_frontend_host_port"));
        assertTrue(workflow.contains("FEATUREPLANT_METRICS_HOST_PORT=$q_featureplant_metrics_host_port"));
    }

    private static String read(String path) throws Exception {
        Path file = Path.of(path);
        Assumptions.assumeTrue(
                Files.exists(file),
                "script fixture not present in this build context: " + path);
        return Files.readString(file);
    }
}
