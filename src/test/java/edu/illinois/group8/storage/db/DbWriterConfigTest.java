package edu.illinois.group8.storage.db;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbWriterConfigTest {
    @Test
    void defaultsToDisabledWithQueueAndBatchDefaults() {
        DbWriterConfig config = DbWriterConfig.from(Map.of());

        assertFalse(config.enabled());
        assertEquals("", config.databaseUrl());
        assertEquals("", config.databaseUser());
        assertEquals("", config.databasePassword());
        assertEquals(250_000, config.queueCapacity());
        assertEquals(500, config.batchSize());
    }

    @Test
    void parsesEnabledValuesAndNormalizesBlankCredentials() {
        DbWriterConfig config = DbWriterConfig.from(Map.of(
            DbWriterConfig.ENABLED_ENV, " true ",
            DbWriterConfig.DATABASE_URL_ENV, " jdbc:postgresql://localhost/kalshi_test ",
            DbWriterConfig.DATABASE_USER_ENV, " ",
            DbWriterConfig.DATABASE_PASSWORD_ENV, "\t",
            DbWriterConfig.QUEUE_CAPACITY_ENV, "64",
            DbWriterConfig.BATCH_SIZE_ENV, "8"
        ));

        assertTrue(config.enabled());
        assertEquals("jdbc:postgresql://localhost/kalshi_test", config.databaseUrl());
        assertEquals("", config.databaseUser());
        assertEquals("", config.databasePassword());
        assertEquals(64, config.queueCapacity());
        assertEquals(8, config.batchSize());
    }

    @Test
    void invalidQueueCapacityThrows() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DbWriterConfig.from(Map.of(DbWriterConfig.QUEUE_CAPACITY_ENV, "0"))
        );
    }

    @Test
    void invalidBatchSizeThrows() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DbWriterConfig.from(Map.of(DbWriterConfig.BATCH_SIZE_ENV, "-1"))
        );
    }

    @Test
    void invalidEnabledValueThrows() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DbWriterConfig.from(Map.of(DbWriterConfig.ENABLED_ENV, "treu"))
        );
    }

    @Test
    void enabledConfigWithoutUrlThrows() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DbWriterConfig.from(Map.of(
                DbWriterConfig.ENABLED_ENV, "true",
                DbWriterConfig.DATABASE_URL_ENV, " "
            ))
        );
    }
}
