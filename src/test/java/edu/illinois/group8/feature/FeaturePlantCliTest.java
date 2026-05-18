package edu.illinois.group8.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturePlantCliTest {
    @Test
    void dbSourceRequiresDatabaseUrl() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> FeaturePlantCli.main(new String[] {"--source=db", "--db-url="})
        );

        assertTrue(thrown.getMessage().contains("FEATUREPLANT_DB_URL"));
    }
}
