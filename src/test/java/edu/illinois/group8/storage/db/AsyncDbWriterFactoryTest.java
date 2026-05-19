package edu.illinois.group8.storage.db;

import edu.illinois.group8.metrics.BackendMetrics;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncDbWriterFactoryTest {
    @Test
    void disabledConfigReturnsDisabledWriterWithoutCreatingStore() {
        AtomicInteger openConnections = new AtomicInteger();
        DbWriterConfig config = DbWriterConfig.from(Map.of());

        AsyncDbWriter writer = AsyncDbWriterFactory.create(config, new BackendMetrics(), () -> {
            openConnections.incrementAndGet();
            throw new AssertionError("connection should not open for disabled writer");
        });

        assertEquals(0, openConnections.get());
        assertEquals(DbOfferResult.DISABLED, writer.offerRaw(rawInput("{\"type\":\"ticker\"}")));
        writer.close();
    }

    @Test
    void enabledConfigConstructsWriterWithoutOpeningConnection() {
        AtomicInteger openConnections = new AtomicInteger();
        DbWriterConfig config = new DbWriterConfig(
            true,
            "jdbc:postgresql://localhost/kalshi_test",
            "",
            "",
            DbWriterConfig.DEFAULT_RAW_SOURCE,
            DbWriterConfig.DEFAULT_RAW_CAPTURE_ID,
            4,
            2
        );

        AsyncDbWriter writer = AsyncDbWriterFactory.create(config, new BackendMetrics(), () -> {
            openConnections.incrementAndGet();
            throw new AssertionError("connection should not open during writer construction");
        });

        try {
            assertEquals(0, openConnections.get());
            assertEquals(4, writer.stats().queueCapacity());
        } finally {
            writer.close();
        }
    }

    @Test
    void databaseUrlAutoEnabledConfigConstructsWriterWithoutOpeningConnection() {
        AtomicInteger openConnections = new AtomicInteger();
        DbWriterConfig config = DbWriterConfig.from(Map.of(
            DbWriterConfig.DATABASE_URL_ENV, "jdbc:postgresql://localhost/kalshi_test"
        ));

        AsyncDbWriter writer = AsyncDbWriterFactory.create(config, new BackendMetrics(), () -> {
            openConnections.incrementAndGet();
            throw new AssertionError("connection should not open during writer construction");
        });

        try {
            assertEquals(0, openConnections.get());
            assertEquals(DbWriterConfig.DEFAULT_QUEUE_CAPACITY, writer.stats().queueCapacity());
        } finally {
            writer.close();
        }
    }

    @Test
    void explicitFalseWithDatabaseUrlReturnsDisabledWriterWithoutCreatingStore() {
        AtomicInteger openConnections = new AtomicInteger();
        DbWriterConfig config = DbWriterConfig.from(Map.of(
            DbWriterConfig.ENABLED_ENV, "false",
            DbWriterConfig.DATABASE_URL_ENV, "jdbc:postgresql://localhost/kalshi_test"
        ));

        AsyncDbWriter writer = AsyncDbWriterFactory.create(config, new BackendMetrics(), () -> {
            openConnections.incrementAndGet();
            throw new AssertionError("connection should not open for explicitly disabled writer");
        });

        assertEquals(0, openConnections.get());
        assertEquals(DbOfferResult.DISABLED, writer.offerRaw(rawInput("{\"type\":\"ticker\"}")));
        writer.close();
    }

    private static RawWsDbEventInput rawInput(String rawPayload) {
        return new RawWsDbEventInput(
            "kalshi-ws",
            "capture-1",
            "connection-1",
            1L,
            2L,
            Instant.parse("2026-05-19T00:00:00Z"),
            rawPayload,
            null
        );
    }

}
