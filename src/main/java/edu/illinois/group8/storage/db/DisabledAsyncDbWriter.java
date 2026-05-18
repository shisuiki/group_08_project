package edu.illinois.group8.storage.db;

public final class DisabledAsyncDbWriter implements AsyncDbWriter {
    static final DisabledAsyncDbWriter INSTANCE = new DisabledAsyncDbWriter();

    private DisabledAsyncDbWriter() {
    }

    @Override
    public DbOfferResult offerRaw(RawWsDbEventInput input) {
        return DbOfferResult.DISABLED;
    }

    @Override
    public DbOfferResult offerCanonical(CanonicalDbEvent event) {
        return DbOfferResult.DISABLED;
    }

    @Override
    public DbWriterStats stats() {
        return DbWriterStats.empty();
    }

    @Override
    public void close() {
    }
}
