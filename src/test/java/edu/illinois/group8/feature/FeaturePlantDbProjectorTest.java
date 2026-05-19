package edu.illinois.group8.feature;

import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.CanonicalDbCursor;
import edu.illinois.group8.storage.db.CanonicalDbEventReader;
import edu.illinois.group8.storage.db.CanonicalDbReadEvent;
import edu.illinois.group8.storage.db.CanonicalDbReadRequest;
import edu.illinois.group8.storage.db.FeatureOutputDbEvent;
import edu.illinois.group8.storage.db.FeatureOutputProjectionStore;
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
        private boolean crashAfterWritingBeforeCursor;
        private final List<String> actions = new ArrayList<>();
        private final Set<String> uniqueFeatureEventIds = new LinkedHashSet<>();

        private FakeProjectionStore(CanonicalDbCursor cursor) {
            this.cursor = Optional.ofNullable(cursor);
        }

        @Override
        public Optional<CanonicalDbCursor> loadCursor(String cursorName) {
            return cursor;
        }

        @Override
        public void commitProjection(String cursorName, CanonicalDbCursor cursor, List<FeatureOutputDbEvent> outputs) {
            if (failNextCommit) {
                failNextCommit = false;
                throw new IllegalStateException("db unavailable");
            }
            for (FeatureOutputDbEvent output : outputs) {
                uniqueFeatureEventIds.add(output.featureEventId());
            }
            if (crashAfterWritingBeforeCursor) {
                crashAfterWritingBeforeCursor = false;
                throw new IllegalStateException("crash before cursor commit");
            }
            actions.add("commit outputs=" + outputs.size() + " cursor=" + cursor.lastCommitSeq());
            this.cursor = Optional.of(cursor);
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
