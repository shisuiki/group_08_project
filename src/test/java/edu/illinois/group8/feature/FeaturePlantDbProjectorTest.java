package edu.illinois.group8.feature;

import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.CanonicalDbCursor;
import edu.illinois.group8.storage.db.CanonicalDbEventReader;
import edu.illinois.group8.storage.db.CanonicalDbReadEvent;
import edu.illinois.group8.storage.db.CanonicalDbReadRequest;
import edu.illinois.group8.storage.db.FeatureOutputDbEvent;
import edu.illinois.group8.storage.db.FeatureOutputProjectionStore;
import edu.illinois.group8.storage.db.FeaturePlantProjectorLease;
import edu.illinois.group8.storage.db.LatestMarketState;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturePlantDbProjectorTest {
    @Test
    void dbWriteSuccessAdvancesCursorOnlyAfterProjectionCommit() {
        FakeReader reader = new FakeReader(List.of(bboEvent(6L, "bbo-6")));
        FakeProjectionStore store = new FakeProjectionStore(new CanonicalDbCursor(5L));
        BackendMetrics metrics = new BackendMetrics();
        FeaturePlantDbProjector projector = newProjector(reader, store, metrics);

        assertEquals(1, projector.poll(10));

        assertEquals(5L, reader.requests.get(0).cursor().lastCommitSeq());
        assertEquals(List.of("commit outputs=1 cursor=6"), store.actions);
        assertEquals(1, store.latestStates.size());
        LatestMarketState state = store.latestStates.get(0);
        assertEquals("MARKET-1", state.marketTicker());
        assertEquals(1700000000006L, state.lastEventTsMs());
        assertEquals("bbo-6", state.lastCanonicalEventId());
        assertEquals(6L, state.lastCanonicalCommitSeq());
        assertEquals(440000L, state.bestBidMicros());
        assertEquals(470000L, state.bestAskMicros());
        assertEquals(455000L, state.midpointMicros());
        assertTrue(state.payload().contains("\"bid_price_micros\":440000"));
        assertEquals(6L, store.cursor.orElseThrow().lastCommitSeq());
        assertEquals(6L, projector.cursor().lastCommitSeq());
        assertEquals(1L, metrics.get(
            "featureplant_db_output_events_total",
            BackendMetrics.labels("service", "featureplant", "result", "accepted")
        ));
        assertEquals(1L, metrics.get(
            "featureplant_db_output_events_total",
            BackendMetrics.labels("service", "featureplant", "result", "written")
        ));
        String metricsText = metrics.prometheusText();
        assertTrue(metricsText.contains(
            "featureplant_db_projector_cursor_commit_seq{cursor=\"featureplant-prod\",service=\"featureplant\"} 6\n"
        ));
        assertTrue(metricsText.contains(
            "featureplant_db_projector_lag_events{cursor=\"featureplant-prod\",service=\"featureplant\"} 0\n"
        ));
    }

    @Test
    void dbWriteFailureDoesNotAdvanceCursorAndEventCanBeReprocessed() {
        FakeReader reader = new FakeReader(
            List.of(bboEvent(6L, "bbo-6")),
            List.of(bboEvent(6L, "bbo-6"))
        );
        FakeProjectionStore store = new FakeProjectionStore(new CanonicalDbCursor(5L));
        store.failNextCommit = true;
        FeaturePlantDbProjector projector = newProjector(reader, store, new BackendMetrics());

        assertThrows(IllegalStateException.class, () -> projector.poll(10));

        assertEquals(5L, projector.cursor().lastCommitSeq());
        assertEquals(5L, store.cursor.orElseThrow().lastCommitSeq());
        assertEquals(5L, reader.requests.get(0).cursor().lastCommitSeq());

        assertEquals(1, projector.poll(10));

        assertEquals(5L, reader.requests.get(1).cursor().lastCommitSeq());
        assertEquals(6L, projector.cursor().lastCommitSeq());
        assertEquals(6L, store.cursor.orElseThrow().lastCommitSeq());
    }

    @Test
    void latestMarketStateFailureDoesNotAdvanceCursorAndEventCanBeReprocessed() {
        FakeReader reader = new FakeReader(
            List.of(bboEvent(6L, "bbo-6")),
            List.of(bboEvent(6L, "bbo-6"))
        );
        FakeProjectionStore store = new FakeProjectionStore(new CanonicalDbCursor(5L));
        store.failNextLatestState = true;
        FeaturePlantDbProjector projector = newProjector(reader, store, new BackendMetrics());

        assertThrows(IllegalStateException.class, () -> projector.poll(10));

        assertEquals(5L, projector.cursor().lastCommitSeq());
        assertEquals(5L, store.cursor.orElseThrow().lastCommitSeq());

        assertEquals(1, projector.poll(10));

        assertEquals(6L, projector.cursor().lastCommitSeq());
        assertEquals(6L, store.cursor.orElseThrow().lastCommitSeq());
        assertEquals(1, store.latestStates.size());
        assertEquals(6L, store.latestStates.get(0).lastCanonicalCommitSeq());
    }

    @Test
    void mapperFailureDoesNotAdvanceCursor() {
        FakeReader reader = new FakeReader(List.of(bboEvent(6L, "bbo-6")));
        FakeProjectionStore store = new FakeProjectionStore(new CanonicalDbCursor(5L));
        FeaturePlantDbProjector projector = new FeaturePlantDbProjector(
            reader,
            store,
            List.of(StreamRegistry.byName("derived.top_of_book").orElseThrow()),
            List.of(new InvalidOutputModule()),
            0L,
            false,
            "",
            "featureplant-prod",
            new BackendMetrics()
        );

        assertThrows(IllegalArgumentException.class, () -> projector.poll(10));

        assertEquals(5L, projector.cursor().lastCommitSeq());
        assertEquals(5L, store.cursor.orElseThrow().lastCommitSeq());
        assertEquals(List.of(), store.actions);
    }

    @Test
    void moduleFailureDoesNotAdvanceCursor() {
        FakeReader reader = new FakeReader(List.of(bboEvent(6L, "bbo-6")));
        FakeProjectionStore store = new FakeProjectionStore(new CanonicalDbCursor(5L));
        FeaturePlantDbProjector projector = new FeaturePlantDbProjector(
            reader,
            store,
            List.of(StreamRegistry.byName("derived.top_of_book").orElseThrow()),
            List.of(new FailingModule()),
            0L,
            false,
            "",
            "featureplant-prod",
            new BackendMetrics()
        );

        assertThrows(IllegalStateException.class, () -> projector.poll(10));

        assertEquals(5L, projector.cursor().lastCommitSeq());
        assertEquals(5L, store.cursor.orElseThrow().lastCommitSeq());
        assertEquals(List.of(), store.actions);
    }

    @Test
    void repeatProcessingIsIdempotentByFeatureEventId() {
        FakeReader firstReader = new FakeReader(List.of(bboEvent(6L, "bbo-6")));
        FakeProjectionStore store = new FakeProjectionStore(new CanonicalDbCursor(5L));
        store.crashAfterWritingBeforeCursor = true;
        FeaturePlantDbProjector firstProjector = newProjector(firstReader, store, new BackendMetrics());

        assertThrows(IllegalStateException.class, () -> firstProjector.poll(10));
        assertEquals(1, store.uniqueFeatureEventIds.size());
        assertEquals(5L, store.cursor.orElseThrow().lastCommitSeq());

        FakeReader secondReader = new FakeReader(List.of(bboEvent(6L, "bbo-6")));
        FeaturePlantDbProjector secondProjector = newProjector(secondReader, store, new BackendMetrics());

        assertEquals(1, secondProjector.poll(10));

        assertEquals(1, store.uniqueFeatureEventIds.size());
        assertEquals(6L, store.cursor.orElseThrow().lastCommitSeq());
    }

    @Test
    void closeReleasesProjectorLease() {
        FakeReader reader = new FakeReader(List.of());
        FakeProjectionStore store = new FakeProjectionStore(new CanonicalDbCursor(5L));
        RecordingLease lease = new RecordingLease();
        FeaturePlantDbProjector projector = new FeaturePlantDbProjector(
            reader,
            store,
            List.of("derived.top_of_book"),
            List.of(new BestBidOfferFeatureModule()),
            0L,
            false,
            "",
            "featureplant-prod",
            new BackendMetrics(),
            new edu.illinois.group8.canonical.JsonCanonicalSerializer().mapper(),
            new FeatureOutputDbEventMapper(),
            lease
        );

        projector.close();

        assertEquals(1, lease.closeCalls);
    }

    @Test
    void emptyPollDoesNotCheckLeaseHealth() {
        FakeReader reader = new FakeReader(List.of());
        FakeProjectionStore store = new FakeProjectionStore(new CanonicalDbCursor(5L));
        RecordingLease lease = new RecordingLease();
        lease.failEnsure = true;
        FeaturePlantDbProjector projector = new FeaturePlantDbProjector(
            reader,
            store,
            List.of("derived.top_of_book"),
            List.of(new BestBidOfferFeatureModule()),
            0L,
            false,
            "",
            "featureplant-prod",
            new BackendMetrics(),
            new edu.illinois.group8.canonical.JsonCanonicalSerializer().mapper(),
            new FeatureOutputDbEventMapper(),
            lease
        );

        assertEquals(0, projector.poll(10));

        assertEquals(0, lease.ensureCalls);
        assertEquals(1, reader.requests.size());
        assertEquals(List.of(), store.actions);
        assertEquals(5L, projector.cursor().lastCommitSeq());
    }

    @Test
    void lostLeaseBeforeCommitDoesNotWriteOutputsOrAdvanceCursor() {
        FakeReader reader = new FakeReader(List.of(bboEvent(6L, "bbo-6")));
        FakeProjectionStore store = new FakeProjectionStore(new CanonicalDbCursor(5L));
        RecordingLease lease = new RecordingLease();
        lease.failOnEnsureCall = 1;
        FeaturePlantDbProjector projector = new FeaturePlantDbProjector(
            reader,
            store,
            List.of("derived.top_of_book"),
            List.of(new BestBidOfferFeatureModule()),
            0L,
            false,
            "",
            "featureplant-prod",
            new BackendMetrics(),
            new edu.illinois.group8.canonical.JsonCanonicalSerializer().mapper(),
            new FeatureOutputDbEventMapper(),
            lease
        );

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> projector.poll(10));

        assertTrue(thrown.getMessage().contains("lease lost"));
        assertEquals(1, lease.ensureCalls);
        assertEquals(1, reader.requests.size());
        assertEquals(List.of(), store.actions);
        assertEquals(5L, store.cursor.orElseThrow().lastCommitSeq());
        assertEquals(5L, projector.cursor().lastCommitSeq());
    }

    private static FeaturePlantDbProjector newProjector(
        FakeReader reader,
        FakeProjectionStore store,
        BackendMetrics metrics
    ) {
        return new FeaturePlantDbProjector(
            reader,
            store,
            List.of(StreamRegistry.byName("derived.top_of_book").orElseThrow()),
            List.of(new BestBidOfferFeatureModule()),
            0L,
            false,
            "",
            "featureplant-prod",
            metrics
        );
    }

    private static CanonicalDbReadEvent bboEvent(long commitSeq, String eventId) {
        return new CanonicalDbReadEvent(
            commitSeq,
            eventId,
            null,
            null,
            "derived.top_of_book",
            "top_of_book_update",
            1,
            "MARKET-1",
            1700000000000L + commitSeq,
            123L,
            456L,
            """
                {"event_id":"%s","event_type":"top_of_book_update","schema_version":1,"stream_name":"derived.top_of_book","metadata":{"source":"kalshi","market_ticker":"MARKET-1","event_ts_ms":%d},"bid_price_micros":440000,"bid_quantity_micros":1000000,"ask_price_micros":470000,"ask_quantity_micros":2000000,"crossed":false}
                """.formatted(eventId, 1700000000000L + commitSeq).trim()
        );
    }

    private static final class FakeReader implements CanonicalDbEventReader {
        private final Queue<List<CanonicalDbReadEvent>> responses = new ArrayDeque<>();
        private final List<CanonicalDbReadRequest> requests = new ArrayList<>();

        @SafeVarargs
        private FakeReader(List<CanonicalDbReadEvent>... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public List<CanonicalDbReadEvent> read(CanonicalDbReadRequest request) {
            requests.add(request);
            return responses.isEmpty() ? List.of() : responses.remove();
        }
    }

    private static final class FakeProjectionStore implements FeatureOutputProjectionStore {
        private Optional<CanonicalDbCursor> cursor;
        private boolean failNextCommit;
        private boolean failNextLatestState;
        private boolean crashAfterWritingBeforeCursor;
        private final List<String> actions = new ArrayList<>();
        private final Set<String> uniqueFeatureEventIds = new LinkedHashSet<>();
        private final List<LatestMarketState> latestStates = new ArrayList<>();

        private FakeProjectionStore(CanonicalDbCursor cursor) {
            this.cursor = Optional.ofNullable(cursor);
        }

        @Override
        public Optional<CanonicalDbCursor> loadCursor(String cursorName) {
            return cursor;
        }

        @Override
        public void commitProjection(
            String cursorName,
            CanonicalDbCursor cursor,
            List<FeatureOutputDbEvent> outputs,
            List<LatestMarketState> latestStates
        ) {
            if (failNextCommit) {
                failNextCommit = false;
                throw new IllegalStateException("db unavailable");
            }
            for (FeatureOutputDbEvent output : outputs) {
                uniqueFeatureEventIds.add(output.featureEventId());
            }
            if (failNextLatestState) {
                failNextLatestState = false;
                throw new IllegalStateException("latest state unavailable");
            }
            this.latestStates.addAll(latestStates);
            if (crashAfterWritingBeforeCursor) {
                crashAfterWritingBeforeCursor = false;
                throw new IllegalStateException("crash before cursor commit");
            }
            actions.add("commit outputs=" + outputs.size() + " cursor=" + cursor.lastCommitSeq());
            this.cursor = Optional.of(cursor);
        }
    }

    private static final class RecordingLease implements FeaturePlantProjectorLease {
        private int closeCalls;
        private int ensureCalls;
        private boolean failEnsure;
        private int failOnEnsureCall;

        @Override
        public void ensureHeld() {
            ensureCalls++;
            if (failEnsure || ensureCalls == failOnEnsureCall) {
                throw new IllegalStateException("lease lost");
            }
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private static final class InvalidOutputModule implements FeatureModule {
        @Override
        public String name() {
            return "feature.invalid";
        }

        @Override
        public java.util.Set<String> inputStreams() {
            return java.util.Set.of("derived.top_of_book");
        }

        @Override
        public void onEvent(CanonicalEnvelope envelope, FeatureOutputCollector collector) {
            collector.emit(new FeatureOutput(null, "feature.invalid", "MARKET-1", 1L, "source-1", Map.of("x", 1)));
        }
    }

    private static final class FailingModule implements FeatureModule {
        @Override
        public String name() {
            return "feature.failing";
        }

        @Override
        public java.util.Set<String> inputStreams() {
            return java.util.Set.of("derived.top_of_book");
        }

        @Override
        public void onEvent(CanonicalEnvelope envelope, FeatureOutputCollector collector) {
            throw new IllegalStateException("module failed");
        }
    }
}
