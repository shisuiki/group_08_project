package edu.illinois.group8.feature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturePlantCliTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultDbSourceRequiresDatabaseUrl() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.main(new String[] {"--db-url="})
        );

        assertTrue(thrown.getMessage().contains("FEATUREPLANT_DB_URL"));
    }

    @Test
    void explicitRecordingSourceAliasesDoNotRequireDatabaseUrl() {
        for (String source : List.of("recording", "history", "storage")) {
            FeaturePlantCli.main(new String[] {
                "--source=" + source,
                "--db-url=",
                "--root=" + tempDir,
                "--max-events=10",
                "--run-once"
            });
        }
    }
}
