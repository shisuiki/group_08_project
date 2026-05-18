package edu.illinois.group8.storage.db;

import com.fasterxml.jackson.databind.JsonNode;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.TickerUpdate;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.KalshiCanonicalParser;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncDbWriterTest {
    @Test
    void disabledWriterReturnsDisabledAndDoesNotCallStore() {
        RecordingStore store = new RecordingStore();
        AsyncDbWriter writer = AsyncDbWriter.disabled();

        assertEquals(DbOfferResult.DISABLED, writer.offerRaw(rawInput(rawPayload("disabled"))));
        assertEquals(DbOfferResult.DISABLED, writer.offerCanonical(canonicalDbEvent("canonical-disabled")));
        assertEquals(DbOfferResult.DISABLED, writer.offerCanonicalEvent(canonicalEvent("canonical-event-disabled")));
        writer.close();

        assertEquals(0, store.rawInsertCalls);
        assertEquals(0, store.canonicalInsertCalls);
        assertEquals(DbWriterStats.empty(), writer.stats());
    }

    @Test
    void boundedQueueDropsQuicklyWhenWorkerIsBlocked() throws Exception {
        RecordingStore store = new RecordingStore();
        store.blockRaw.set(true);
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 1, 1, metrics)) {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("blocking"))));
            assertTrue(store.rawBatchStarted.await(5, TimeUnit.SECONDS), "worker should enter the slow store");

            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("queued"))));
            long startNs = System.nanoTime();
            DbOfferResult result = writer.offerRaw(rawInput(null));
            long elapsedNs = System.nanoTime() - startNs;

            assertEquals(DbOfferResult.DROPPED_FULL, result);
            assertTrue(elapsedNs < TimeUnit.MILLISECONDS.toNanos(500), "offer should not wait on the store");
            assertEquals(2L, writer.stats().rawAccepted());
            assertEquals(1L, writer.stats().rawDropped());
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.RAW_DROPPED_COUNTER));

            store.releaseRaw.countDown();
            assertEventually(() -> store.rawEvents.size() == 2, "queued raw event should flush after release");
        }
    }

    @Test
    void acceptedRawAndCanonicalEventsAreWrittenAndCounted() throws Exception {
        RecordingStore store = new RecordingStore();
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 8, 4, metrics)) {
            String rawPayload = rawPayload("ok");

            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload)));
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonical(canonicalDbEvent("canonical-ok")));

            assertEventually(
                () -> writer.stats().rawWritten() == 1L && writer.stats().canonicalWritten() == 1L,
                "writer should hand accepted events to the store"
            );

            assertEquals(
                List.of(KalshiCanonicalParser.rawEventId(rawPayload)),
                store.rawEvents.stream().map(RawWsDbEvent::rawEventId).toList()
            );
            assertEquals(List.of(rawPayload), store.rawEvents.stream().map(RawWsDbEvent::rawPayload).toList());
            assertEquals(
                List.of("canonical-ok"),
                store.canonicalEvents.stream().map(CanonicalDbEvent::eventId).toList()
            );
            assertEquals(1L, writer.stats().rawAccepted());
            assertEquals(1L, writer.stats().canonicalAccepted());
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.RAW_ACCEPTED_COUNTER));
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.CANONICAL_ACCEPTED_COUNTER));
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.RAW_WRITTEN_COUNTER));
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.CANONICAL_WRITTEN_COUNTER));
        }
    }

    @Test
    void acceptedCanonicalEventIsMappedByWorkerAndWritten() throws Exception {
        RecordingStore store = new RecordingStore();
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 8, 4, metrics)) {
            CanonicalEvent event = canonicalEvent("canonical-event-ok");

            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonicalEvent(event));

            assertEventually(
                () -> writer.stats().canonicalWritten() == 1L,
                "worker should map and write accepted canonical event"
            );

            CanonicalDbEvent dbEvent = store.canonicalEvents.get(0);
            assertEquals("canonical-event-ok", dbEvent.eventId());
            assertEquals("raw-1", dbEvent.rawEventId());
            assertEquals("replay-1", dbEvent.replayId());
            assertEquals(EventType.TICKER_UPDATE.streamName(), dbEvent.streamName());
            assertEquals(EventType.TICKER_UPDATE.eventType(), dbEvent.eventType());
            assertEquals(EventType.TICKER_UPDATE.schemaVersion(), dbEvent.schemaVersion());
            assertEquals("MARKET-1", dbEvent.marketTicker());
            assertEquals(1_700_000_000_000L, dbEvent.eventTsMs());
            assertEquals(123L, dbEvent.ingestTsNs());
            assertEquals(456L, dbEvent.publishTsNs());
            JsonNode payload = new JsonCanonicalSerializer().mapper().readTree(dbEvent.payload());
            assertEquals("canonical-event-ok", payload.path("event_id").asText());
            assertEquals(EventType.TICKER_UPDATE.streamName(), payload.path("stream_name").asText());
            assertEquals("MARKET-1", payload.path("metadata").path("market_ticker").asText());
            assertEquals("raw-1", payload.path("metadata").path("raw_event_id").asText());
            assertEquals(1L, writer.stats().canonicalAccepted());
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.CANONICAL_ACCEPTED_COUNTER));
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.CANONICAL_WRITTEN_COUNTER));
        }
    }

    @Test
    void canonicalEventOfferDropsQuicklyWhenQueueIsFull() throws Exception {
        RecordingStore store = new RecordingStore();
        store.blockRaw.set(true);
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 1, 1, metrics)) {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("blocking"))));
            assertTrue(store.rawBatchStarted.await(5, TimeUnit.SECONDS), "worker should enter the slow store");

            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonicalEvent(canonicalEvent("canonical-queued")));
            long startNs = System.nanoTime();
            DbOfferResult result = writer.offerCanonicalEvent(canonicalEvent("canonical-dropped"));
            long elapsedNs = System.nanoTime() - startNs;

            assertEquals(DbOfferResult.DROPPED_FULL, result);
            assertTrue(elapsedNs < TimeUnit.MILLISECONDS.toNanos(500), "offer should not wait on the store");
            assertEquals(1L, writer.stats().canonicalAccepted());
            assertEquals(1L, writer.stats().canonicalDropped());
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.CANONICAL_DROPPED_COUNTER));

            store.releaseRaw.countDown();
            assertEventually(() -> store.canonicalEvents.size() == 1, "queued canonical event should flush after release");
        }
    }

    @Test
    void closedWriterRejectsNewOffers() {
        RecordingStore store = new RecordingStore();
        BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 4, 1, new BackendMetrics());

        writer.close();

        assertEquals(DbOfferResult.DISABLED, writer.offerRaw(rawInput(rawPayload("after-close"))));
        assertEquals(DbOfferResult.DISABLED, writer.offerCanonical(canonicalDbEvent("canonical-after-close")));
        assertEquals(DbOfferResult.DISABLED, writer.offerCanonicalEvent(canonicalEvent("canonical-event-after-close")));
        assertEquals(0L, writer.stats().rawAccepted());
        assertEquals(0L, writer.stats().canonicalAccepted());
    }

    @Test
    void storeExceptionIsCountedAndDoesNotEscapeOffer() throws Exception {
        RecordingStore store = new RecordingStore();
        store.failRaw.set(true);
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 4, 1, metrics)) {
            assertDoesNotThrow(() ->
                assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("store-fails"))))
            );

            assertEventually(
                () -> writer.stats().failedBatches() == 1L,
                "failed store batch should be counted by the worker"
            );
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.BATCH_FAILED_COUNTER));
            assertEquals(0L, writer.stats().rawWritten());
        }
    }

    @Test
    void mapperExceptionIsCountedByWorkerAndDoesNotEscapeOffer() throws Exception {
        RecordingStore store = new RecordingStore();
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 4, 1, metrics)) {
            assertDoesNotThrow(() ->
                assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(null)))
            );

            assertEventually(
                () -> writer.stats().failedBatches() == 1L,
                "failed mapper batch should be counted by the worker"
            );
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.BATCH_FAILED_COUNTER));
            assertEquals(0L, writer.stats().rawWritten());
            assertEquals(0, store.rawInsertCalls);
        }
    }

    @Test
    void rawMapperExceptionDoesNotSkipCanonicalWrite() throws Exception {
        RecordingStore store = new RecordingStore();
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 4, 2, metrics)) {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(null)));
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonical(canonicalDbEvent("canonical-after-raw-failure")));

            assertEventually(
                () -> writer.stats().failedBatches() == 1L && writer.stats().canonicalWritten() == 1L,
                "raw mapper failure should not prevent canonical writes from the worker"
            );
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.BATCH_FAILED_COUNTER));
            assertEquals(
                List.of("canonical-after-raw-failure"),
                store.canonicalEvents.stream().map(CanonicalDbEvent::eventId).toList()
            );
            assertEquals(0L, writer.stats().rawWritten());
        }
    }

    @Test
    void canonicalEventMapperExceptionDoesNotSkipMappedCanonicalWrite() throws Exception {
        RecordingStore store = new RecordingStore();
        store.blockRaw.set(true);
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 4, 3, metrics)) {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("blocking"))));
            assertTrue(store.rawBatchStarted.await(5, TimeUnit.SECONDS), "worker should enter the slow store");

            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonical(canonicalDbEvent("mapped-canonical-ok")));
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonicalEvent(canonicalEventWithoutMetadata("bad-canonical")));

            store.releaseRaw.countDown();

            assertEventually(
                () -> writer.stats().failedBatches() == 1L && writer.stats().canonicalWritten() == 1L,
                "canonical mapper failure should not prevent pre-mapped canonical writes"
            );
            assertEquals(
                List.of("mapped-canonical-ok"),
                store.canonicalEvents.stream().map(CanonicalDbEvent::eventId).toList()
            );
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.BATCH_FAILED_COUNTER));
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.CANONICAL_WRITTEN_COUNTER));
        }
    }

    private static RawWsDbEventInput rawInput(String rawPayload) {
        return new RawWsDbEventInput(
            "kalshi-ws",
            "capture-1",
            "connection-1",
            1L,
            2L,
            Instant.parse("2026-05-19T00:00:00Z"),
            rawPayload,
            "accepted"
        );
    }

    private static String rawPayload(String suffix) {
        return "{\"type\":\"ticker\",\"seq\":3,\"msg\":{\"market_ticker\":\"MARKET-" + suffix + "\"}}";
    }

    private static CanonicalDbEvent canonicalDbEvent(String eventId) {
        return new CanonicalDbEvent(
            eventId,
            "raw-ok",
            null,
            "canonical.ticker",
            "ticker",
            1,
            "MARKET-1",
            10L,
            20L,
            30L,
            "{\"market_ticker\":\"MARKET-1\"}"
        );
    }

    private static CanonicalEvent canonicalEvent(String eventId) {
        return new TickerUpdate(
            eventId,
            new EventMetadata(
                "kalshi",
                "ticker",
                11L,
                22L,
                "MARKET-1",
                "market-id-1",
                1_700_000_000_000L,
                123L,
                456L,
                "raw-1",
                "replay-1"
            ),
            480_000L,
            450_000L,
            530_000L,
            null,
            null,
            null,
            null,
            null
        );
    }

    private static CanonicalEvent canonicalEventWithoutMetadata(String eventId) {
        return new TickerUpdate(
            eventId,
            null,
            480_000L,
            450_000L,
            530_000L,
            null,
            null,
            null,
            null,
            null
        );
    }

    private static void assertEventually(BooleanSupplier condition, String message) throws Exception {
        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNs) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5L);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static final class RecordingStore implements AcceptedEventStore {
        private final AtomicBoolean blockRaw = new AtomicBoolean();
        private final AtomicBoolean failRaw = new AtomicBoolean();
        private final CountDownLatch rawBatchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRaw = new CountDownLatch(1);
        private final List<RawWsDbEvent> rawEvents = new CopyOnWriteArrayList<>();
        private final List<CanonicalDbEvent> canonicalEvents = new CopyOnWriteArrayList<>();
        private int rawInsertCalls;
        private int canonicalInsertCalls;

        @Override
        public synchronized void insertRawBatch(List<RawWsDbEvent> events) throws Exception {
            rawInsertCalls++;
            rawBatchStarted.countDown();
            if (blockRaw.get()) {
                assertTrue(releaseRaw.await(5, TimeUnit.SECONDS), "test did not release raw insert");
            }
            if (failRaw.get()) {
                throw new IllegalStateException("raw insert failed");
            }
            rawEvents.addAll(events);
        }

        @Override
        public synchronized void insertCanonicalBatch(List<CanonicalDbEvent> events) {
            canonicalInsertCalls++;
            canonicalEvents.addAll(events);
        }
    }
}
