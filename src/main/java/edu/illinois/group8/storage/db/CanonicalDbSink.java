package edu.illinois.group8.storage.db;

import edu.illinois.group8.canonical.CanonicalEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CanonicalDbSink implements AutoCloseable {
    private final AsyncDbWriter writer;
    private final AtomicBoolean closed = new AtomicBoolean();

    public CanonicalDbSink(AsyncDbWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public DbOfferResult offer(CanonicalEvent event) {
        return writer.offerCanonicalEvent(event);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            writer.close();
        }
    }

    public static CanonicalDbSink disabled() {
        return new CanonicalDbSink(AsyncDbWriter.disabled());
    }
}
