package edu.illinois.group8.storage.db;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.SerializedCanonicalEvent;

public interface AsyncDbWriter extends AutoCloseable {
    DbOfferResult offerRaw(RawWsDbEventInput input);

    DbOfferResult offerCanonical(CanonicalDbEvent event);

    DbOfferResult offerCanonicalEvent(CanonicalEvent event);

    default DbOfferResult offerSerializedCanonicalEvent(SerializedCanonicalEvent event) {
        return offerCanonicalEvent(event.event());
    }

    DbWriterStats stats();

    @Override
    void close();

    static AsyncDbWriter disabled() {
        return DisabledAsyncDbWriter.INSTANCE;
    }
}
