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
        assertEquals(DbOfferResult.DISABLED, writer.offerRaw(rawEvent("raw-disabled-factory")));
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

    private static RawWsDbEvent rawEvent(String rawEventId) {
        return new RawWsDbEvent(
            rawEventId,
            "kalshi-ws",
            "capture-1",
            "connection-1",
            1L,
            2L,
            Instant.parse("2026-05-19T00:00:00Z"),
            "MARKET-1",
            "ticker",
            3L,
            "sha256-" + rawEventId,
            "{\"type\":\"ticker\"}",
            null
        );
    }

}
