package edu.illinois.group8.storage.db;

public interface AsyncDbWriter extends AutoCloseable {
    DbOfferResult offerRaw(RawWsDbEventInput input);

    DbOfferResult offerCanonical(CanonicalDbEvent event);

    DbWriterStats stats();

    @Override
    void close();

    static AsyncDbWriter disabled() {
        return DisabledAsyncDbWriter.INSTANCE;
    }
}
