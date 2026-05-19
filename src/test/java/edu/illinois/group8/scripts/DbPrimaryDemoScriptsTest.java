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

    private static String read(String path) throws Exception {
        Path file = Path.of(path);
        Assumptions.assumeTrue(
                Files.exists(file),
                "script fixture not present in this build context: " + path);
        return Files.readString(file);
    }
}
