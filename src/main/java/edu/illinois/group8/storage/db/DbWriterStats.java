package edu.illinois.group8.storage.db;

public record DbWriterStats(
    long rawAccepted,
    long rawDropped,
    long rawWritten,
    long canonicalAccepted,
    long canonicalDropped,
    long canonicalWritten,
    long failedBatches,
    int queueDepth,
    int queueCapacity
) {
    public static DbWriterStats empty() {
        return new DbWriterStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0);
    }
}
