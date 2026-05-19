package edu.illinois.group8.backfill;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.storage.db.AcceptedEventStore;
import edu.illinois.group8.storage.db.CanonicalDbEvent;
import edu.illinois.group8.storage.db.CanonicalDbEventMapper;

import java.util.List;
import java.util.Objects;

final class DbCanonicalBackfillSink implements CanonicalBackfillSink {
    private final AcceptedEventStore store;
    private final CanonicalDbEventMapper mapper;

    DbCanonicalBackfillSink(AcceptedEventStore store) {
        this(store, new CanonicalDbEventMapper());
    }

    DbCanonicalBackfillSink(AcceptedEventStore store, CanonicalDbEventMapper mapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void writeBatch(List<CanonicalEvent> events, long receiveTsNs) throws Exception {
        if (events.isEmpty()) {
            return;
        }
        List<CanonicalDbEvent> dbEvents = events.stream()
            .map(mapper::toDbEvent)
            .toList();
        store.insertCanonicalBatch(dbEvents);
    }
}
