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
                "--output=stdout",
                "--root=" + tempDir,
                "--max-events=10",
                "--run-once"
            });
        }
    }

    @Test
    void dbOutputRequiresDatabaseUrlEvenForRecordingSource() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.main(new String[] {
                "--source=recording",
                "--root=" + tempDir,
                "--output=db",
                "--db-url=",
                "--max-events=0",
                "--run-once"
            })
        );

        assertTrue(thrown.getMessage().contains("FEATUREPLANT_DB_URL"));
        assertTrue(thrown.getMessage().contains("FEATUREPLANT_OUTPUT"));
    }

    @Test
    void dbOutputWithDatabaseUrlCanRunEmptyRecordingSource() {
        FeaturePlantCli.main(new String[] {
            "--source=recording",
            "--root=" + tempDir,
            "--output=db",
            "--db-url=jdbc:postgresql://db:5432/kalshi",
            "--max-events=0",
            "--run-once"
        });
    }

    @Test
    void combinedOutputWithDatabaseUrlCanRunEmptyRecordingSource() {
        FeaturePlantCli.main(new String[] {
            "--source=recording",
            "--root=" + tempDir,
            "--output=stdout,db",
            "--db-url=jdbc:postgresql://db:5432/kalshi",
            "--max-events=0",
            "--run-once"
        });
    }

    @Test
    void unsupportedOutputModeIsRejected() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.main(new String[] {
                "--source=recording",
                "--root=" + tempDir,
                "--output=kafka",
                "--max-events=0",
                "--run-once"
            })
        );

        assertTrue(thrown.getMessage().contains("FEATUREPLANT_OUTPUT"));
    }
}
