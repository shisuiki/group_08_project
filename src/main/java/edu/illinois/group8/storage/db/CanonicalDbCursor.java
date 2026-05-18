package edu.illinois.group8.storage.db;

public record CanonicalDbCursor(long lastCommitSeq) {
    private static final CanonicalDbCursor START = new CanonicalDbCursor(0L);

    public CanonicalDbCursor {
        if (lastCommitSeq < 0L) {
            throw new IllegalArgumentException("lastCommitSeq must be non-negative");
        }
    }

    public static CanonicalDbCursor start() {
        return START;
    }
}
