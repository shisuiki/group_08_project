package edu.illinois.group8.backfill;

import java.time.Instant;

interface RawRestBackfillSink {
    void write(String endpoint, String ticker, String rawPayload, long fetchTsNs, Instant fetchWallTs) throws Exception;
}
