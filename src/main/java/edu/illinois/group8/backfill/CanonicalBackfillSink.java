package edu.illinois.group8.backfill;

import edu.illinois.group8.canonical.CanonicalEvent;

import java.util.List;

interface CanonicalBackfillSink {
    void writeBatch(List<CanonicalEvent> events, long receiveTsNs) throws Exception;
}
