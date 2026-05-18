package edu.illinois.group8.cluster;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.CanonicalDbEvent;
import edu.illinois.group8.storage.db.DbOfferResult;
import edu.illinois.group8.storage.db.DbWriterConfig;
import edu.illinois.group8.storage.db.DbWriterStats;
import edu.illinois.group8.storage.db.RawWsDbEventInput;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ESBClusteredServiceTest {
    @Test
    void initializesCanonicalDbSinkFromConfigFactoryAndClosesItOnceOnTerminate() {
        DbWriterConfig config = DbWriterConfig.from(java.util.Map.of());
        BackendMetrics metrics = new BackendMetrics();
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        AtomicReference<DbWriterConfig> seenConfig = new AtomicReference<>();
        AtomicReference<BackendMetrics> seenMetrics = new AtomicReference<>();
        ESBClusteredService service = new ESBClusteredService(
            "aeron-dir",
            "localhost",
            () -> config,
            (providedConfig, providedMetrics) -> {
                seenConfig.set(providedConfig);
                seenMetrics.set(providedMetrics);
                return writer;
            }
        );

        service.initializeCanonicalDbSink(metrics);
        service.onTerminate(null);
        service.onTerminate(null);

        assertSame(config, seenConfig.get());
        assertSame(metrics, seenMetrics.get());
        assertEquals(1, writer.closeCalls);
    }

    private static final class RecordingAsyncDbWriter implements AsyncDbWriter {
        private int closeCalls;

        @Override
        public DbOfferResult offerRaw(RawWsDbEventInput input) {
            throw new UnsupportedOperationException("raw writes are out of scope");
        }

        @Override
        public DbOfferResult offerCanonical(CanonicalDbEvent event) {
            throw new UnsupportedOperationException("pre-mapped canonical writes are out of scope");
        }

        @Override
        public DbOfferResult offerCanonicalEvent(CanonicalEvent event) {
            return DbOfferResult.ACCEPTED;
        }

        @Override
        public DbWriterStats stats() {
            return DbWriterStats.empty();
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
