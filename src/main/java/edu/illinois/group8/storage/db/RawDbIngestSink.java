package edu.illinois.group8.storage.db;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class RawDbIngestSink implements AutoCloseable {
    private static final String INGEST_STATUS_QUEUED = "queued";

    private final AsyncDbWriter writer;
    private final String source;
    private final String captureId;
    private final AtomicLong connectionCounter = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RawDbIngestSink(AsyncDbWriter writer, String source, String captureId) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.source = Objects.requireNonNull(source, "source");
        this.captureId = Objects.requireNonNull(captureId, "captureId");
    }

    public RawDbIngestConnection newConnection() {
        return new RawDbIngestConnection(captureId + "-" + connectionCounter.incrementAndGet());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            writer.close();
        }
    }

    public final class RawDbIngestConnection {
        private final String connectionId;
        private final AtomicLong connectionSequence = new AtomicLong();

        private RawDbIngestConnection(String connectionId) {
            this.connectionId = connectionId;
        }

        public String connectionId() {
            return connectionId;
        }

        public DbOfferResult recordInbound(String rawPayload, long receiveTsNs, Instant receiveWallTs) {
            long sequence = connectionSequence.incrementAndGet();
            return writer.offerRaw(new RawWsDbEventInput(
                source,
                captureId,
                connectionId,
                sequence,
                receiveTsNs,
                receiveWallTs,
                rawPayload,
                INGEST_STATUS_QUEUED
            ));
        }
    }
}
