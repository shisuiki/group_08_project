package edu.illinois.group8.storage.db;

import com.fasterxml.jackson.databind.JsonNode;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.SerializedCanonicalEvent;
import edu.illinois.group8.canonical.TickerUpdate;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.KalshiCanonicalParser;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
        assertEquals(DbOfferResult.DISABLED, writer.offerSerializedCanonicalEvent(serializedCanonicalEvent("serialized-disabled")));
        writer.close();

        assertEquals(0, store.rawInsertCalls.get());
        assertEquals(0, store.canonicalInsertCalls.get());
        assertEquals(DbWriterStats.empty(), writer.stats());
    }

    @Test
    void rawQueueDropsQuicklyWhenRawWorkerIsBlockedWithoutBlockingCanonical() throws Exception {
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

            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonical(canonicalDbEvent("canonical-while-raw-blocked")));
            assertEventually(
                () -> store.canonicalEvents.size() == 1 && writer.stats().canonicalWritten() == 1L,
                "canonical worker should write while raw worker is blocked"
            );

            store.releaseRaw.countDown();
            assertEventually(() -> store.rawEvents.size() == 2, "queued raw event should flush after release");
            long closeStartNs = System.nanoTime();
            writer.close();
            long closeElapsedNs = System.nanoTime() - closeStartNs;

            assertTrue(closeElapsedNs < TimeUnit.MILLISECONDS.toNanos(500), "close should not wait after release");
            assertEquals(2L, writer.stats().rawWritten());
            assertEquals(1L, writer.stats().canonicalWritten());
            assertEquals(0, writer.stats().queueDepth());
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
    void queueDepthGaugesExposeAggregateAndSplitDepths() throws Exception {
        RecordingStore store = new RecordingStore();
        store.blockRaw.set(true);
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 2, 1, metrics)) {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("blocking"))));
            assertTrue(store.rawBatchStarted.await(5, TimeUnit.SECONDS), "worker should enter the slow store");
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("queued"))));

            String queuedText = metrics.prometheusText();
            assertTrue(queuedText.contains(BoundedAsyncDbWriter.RAW_QUEUE_DEPTH_GAUGE + " 1\n"));
            assertTrue(queuedText.contains(BoundedAsyncDbWriter.CANONICAL_QUEUE_DEPTH_GAUGE + " 0\n"));
            assertTrue(queuedText.contains(BoundedAsyncDbWriter.QUEUE_DEPTH_GAUGE + " 1\n"));

            store.releaseRaw.countDown();
            assertEventually(() -> writer.stats().rawWritten() == 2L, "raw queue should drain after release");

            String drainedText = metrics.prometheusText();
            assertTrue(drainedText.contains(BoundedAsyncDbWriter.RAW_QUEUE_DEPTH_GAUGE + " 0\n"));
            assertTrue(drainedText.contains(BoundedAsyncDbWriter.CANONICAL_QUEUE_DEPTH_GAUGE + " 0\n"));
            assertTrue(drainedText.contains(BoundedAsyncDbWriter.QUEUE_DEPTH_GAUGE + " 0\n"));
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
    void acceptedSerializedCanonicalEventUsesPayloadWithoutSerializer() throws Exception {
        RecordingStore store = new RecordingStore();
        BackendMetrics metrics = new BackendMetrics();
        CanonicalDbEventMapper mapper = new CanonicalDbEventMapper(new ThrowingJsonCanonicalSerializer());

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(
            store,
            8,
            4,
            metrics,
            new RawWsDbEventMapper(),
            mapper
        )) {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerSerializedCanonicalEvent(
                serializedCanonicalEvent("serialized-canonical-ok")
            ));

            assertEventually(
                () -> writer.stats().canonicalWritten() == 1L,
                "worker should map serialized canonical event without reserializing"
            );

            CanonicalDbEvent dbEvent = store.canonicalEvents.get(0);
            assertEquals("serialized-canonical-ok", dbEvent.eventId());
            JsonNode payload = new JsonCanonicalSerializer().mapper().readTree(dbEvent.payload());
            assertEquals("serialized-canonical-ok", payload.path("event_id").asText());
            assertEquals(EventType.TICKER_UPDATE.streamName(), payload.path("stream_name").asText());
            assertEquals(1L, writer.stats().canonicalAccepted());
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.CANONICAL_ACCEPTED_COUNTER));
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.CANONICAL_WRITTEN_COUNTER));
            assertEquals(0L, writer.stats().failedBatches());
        }
    }

    @Test
    void closeDrainsAcceptedEventsAndCountsFinalStats() throws Exception {
        RecordingStore store = new RecordingStore();
        store.blockRaw.set(true);
        store.blockCanonical.set(true);
        BackendMetrics metrics = new BackendMetrics();
        BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 16, 8, metrics);
        CountDownLatch closeInvoked = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closeThread = new Thread(() -> {
            try {
                closeInvoked.countDown();
                writer.close();
            } catch (Throwable t) {
                closeFailure.compareAndSet(null, t);
            }
        }, "async-db-writer-close-drain");

        try {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("close-raw-1"))));
            assertTrue(store.rawBatchStarted.await(5, TimeUnit.SECONDS), "worker should block in raw store");
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonical(canonicalDbEvent("close-canonical")));
            assertTrue(store.canonicalBatchStarted.await(5, TimeUnit.SECONDS), "canonical worker should block in canonical store");
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("close-raw-2"))));
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonicalEvent(canonicalEvent("close-canonical-event")));

            closeThread.start();
            assertTrue(closeInvoked.await(5, TimeUnit.SECONDS), "close should start");
            Thread.sleep(50L);
            assertTrue(closeThread.isAlive(), "close should wait for accepted blocked work to drain");

            store.releaseRaw.countDown();
            Thread.sleep(50L);
            assertTrue(closeThread.isAlive(), "close should still wait for canonical worker to drain");
            store.releaseCanonical.countDown();
            closeThread.join(2_000L);

            assertTrue(!closeThread.isAlive(), "close should finish after the store releases");
            assertEquals(null, closeFailure.get());
            assertEquals(2L, writer.stats().rawAccepted());
            assertEquals(2L, writer.stats().rawWritten());
            assertEquals(2L, writer.stats().canonicalAccepted());
            assertEquals(2L, writer.stats().canonicalWritten());
            assertEquals(0, writer.stats().queueDepth());
            assertEquals(2L, metrics.get(BoundedAsyncDbWriter.RAW_WRITTEN_COUNTER));
            assertEquals(2L, metrics.get(BoundedAsyncDbWriter.CANONICAL_WRITTEN_COUNTER));
            assertEquals(2, store.rawEvents.size());
            assertEquals(2, store.canonicalEvents.size());
            assertTrue(
                store.canonicalEvents.stream()
                    .map(CanonicalDbEvent::eventId)
                    .toList()
                    .containsAll(List.of("close-canonical", "close-canonical-event")),
                "close should drain both canonical write forms"
            );
        } finally {
            store.releaseRaw.countDown();
            store.releaseCanonical.countDown();
            writer.close();
        }
    }

    @Test
    void canonicalQueueDropsQuicklyWhenCanonicalWorkerIsBlockedWithoutBlockingRaw() throws Exception {
        RecordingStore store = new RecordingStore();
        store.blockCanonical.set(true);
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 1, 1, metrics)) {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonicalEvent(canonicalEvent("canonical-blocking")));
            assertTrue(store.canonicalBatchStarted.await(5, TimeUnit.SECONDS), "canonical worker should enter the slow store");
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonicalEvent(canonicalEvent("canonical-queued")));
            long startNs = System.nanoTime();
            DbOfferResult result = writer.offerCanonicalEvent(canonicalEvent("canonical-dropped"));
            long elapsedNs = System.nanoTime() - startNs;

            assertEquals(DbOfferResult.DROPPED_FULL, result);
            assertTrue(elapsedNs < TimeUnit.MILLISECONDS.toNanos(500), "offer should not wait on the store");
            assertEquals(2L, writer.stats().canonicalAccepted());
            assertEquals(1L, writer.stats().canonicalDropped());
            assertEquals(1L, metrics.get(BoundedAsyncDbWriter.CANONICAL_DROPPED_COUNTER));

            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("raw-while-canonical-blocked"))));
            assertEventually(
                () -> writer.stats().rawWritten() == 1L,
                "raw worker should write while canonical worker is blocked"
            );

            store.releaseCanonical.countDown();
            assertEventually(() -> store.canonicalEvents.size() == 2, "queued canonical event should flush after release");
            assertEquals(2L, writer.stats().canonicalWritten());
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
        assertEquals(DbOfferResult.DISABLED, writer.offerSerializedCanonicalEvent(serializedCanonicalEvent("serialized-after-close")));
        assertEquals(0L, writer.stats().rawAccepted());
        assertEquals(0L, writer.stats().canonicalAccepted());
    }

    @Test
    void closeUsesSharedDeadlineAcrossWorkers() throws Exception {
        RecordingStore store = new RecordingStore();
        store.blockRaw.set(true);
        store.blockCanonical.set(true);
        BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 4, 1, new BackendMetrics());

        try {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("deadline-raw"))));
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonical(canonicalDbEvent("deadline-canonical")));
            assertTrue(store.rawBatchStarted.await(5, TimeUnit.SECONDS), "raw worker should enter store");
            assertTrue(store.canonicalBatchStarted.await(5, TimeUnit.SECONDS), "canonical worker should enter store");

            long startNs = System.nanoTime();
            writer.close();
            long elapsedNs = System.nanoTime() - startNs;

            assertTrue(
                elapsedNs < TimeUnit.MILLISECONDS.toNanos(3_500L),
                "close should use one shared deadline across both workers"
            );
        } finally {
            store.releaseRaw.countDown();
            store.releaseCanonical.countDown();
            writer.close();
        }
    }

    @Test
    void offerAndCloseRaceDoesNotThrowOrBlock() throws Exception {
        RecordingStore store = new RecordingStore();
        store.blockRaw.set(true);
        BackendMetrics metrics = new BackendMetrics();
        BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 32, 8, metrics);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong acceptedOffers = new AtomicLong();
        AtomicLong droppedOffers = new AtomicLong();
        AtomicLong disabledOffers = new AtomicLong();
        Thread[] offerThreads = new Thread[4];
        Thread closeThread = new Thread(() -> {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS), "race should start");
                writer.close();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "async-db-writer-close-race");

        try {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("race-blocking"))));
            assertTrue(store.rawBatchStarted.await(5, TimeUnit.SECONDS), "worker should block in raw store");
            for (int threadIndex = 0; threadIndex < offerThreads.length; threadIndex++) {
                int workerIndex = threadIndex;
                offerThreads[threadIndex] = new Thread(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS), "race should start");
                        for (int i = 0; i < 100; i++) {
                            countResult(
                                writer.offerRaw(rawInput(rawPayload("race-" + workerIndex + "-" + i))),
                                acceptedOffers,
                                droppedOffers,
                                disabledOffers
                            );
                            countResult(
                                writer.offerCanonicalEvent(canonicalEvent("race-canonical-" + workerIndex + "-" + i)),
                                acceptedOffers,
                                droppedOffers,
                                disabledOffers
                            );
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }, "async-db-writer-offer-race-" + threadIndex);
                offerThreads[threadIndex].start();
            }
            closeThread.start();
            start.countDown();
            for (Thread offerThread : offerThreads) {
                offerThread.join(1_000L);
                assertTrue(!offerThread.isAlive(), "offer thread should not block on close or store work");
            }

            store.releaseRaw.countDown();
            closeThread.join(2_000L);

            assertEquals(800L, acceptedOffers.get() + droppedOffers.get() + disabledOffers.get());
            assertTrue(!closeThread.isAlive(), "close thread should return");
            assertEquals(null, failure.get());
            assertEquals(writer.stats().rawAccepted(), writer.stats().rawWritten());
            assertEquals(writer.stats().canonicalAccepted(), writer.stats().canonicalWritten());
            assertEquals(0, writer.stats().queueDepth());
            String text = metrics.prometheusText();
            assertTrue(text.contains(BoundedAsyncDbWriter.RAW_QUEUE_DEPTH_GAUGE + " 0\n"));
            assertTrue(text.contains(BoundedAsyncDbWriter.CANONICAL_QUEUE_DEPTH_GAUGE + " 0\n"));
            assertTrue(text.contains(BoundedAsyncDbWriter.QUEUE_DEPTH_GAUGE + " 0\n"));
            assertEquals(DbOfferResult.DISABLED, writer.offerRaw(rawInput(rawPayload("after-race-close"))));
            assertEquals(DbOfferResult.DISABLED, writer.offerCanonical(canonicalDbEvent("after-race-close")));
        } finally {
            store.releaseRaw.countDown();
            writer.close();
        }
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
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonical(canonicalDbEvent("canonical-after-raw-store-failure")));

            assertEventually(
                () -> writer.stats().failedBatches() == 1L
                    && writer.stats().canonicalWritten() == 1L
                    && metrics.get(BoundedAsyncDbWriter.BATCH_FAILED_COUNTER) == 1L,
                "failed raw store batch should not prevent canonical worker writes"
            );
            assertEquals(0L, writer.stats().rawWritten());
            assertEquals(
                List.of("canonical-after-raw-store-failure"),
                store.canonicalEvents.stream().map(CanonicalDbEvent::eventId).toList()
            );
        }
    }

    @Test
    void canonicalStoreExceptionDoesNotPreventRawWrite() throws Exception {
        RecordingStore store = new RecordingStore();
        store.failCanonical.set(true);
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 4, 1, metrics)) {
            assertDoesNotThrow(() ->
                assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonical(canonicalDbEvent("canonical-store-fails")))
            );
            assertEquals(DbOfferResult.ACCEPTED, writer.offerRaw(rawInput(rawPayload("raw-after-canonical-store-failure"))));

            assertEventually(
                () -> writer.stats().failedBatches() == 1L
                    && writer.stats().rawWritten() == 1L
                    && metrics.get(BoundedAsyncDbWriter.BATCH_FAILED_COUNTER) == 1L,
                "failed canonical store batch should not prevent raw worker writes"
            );
            assertEquals(0L, writer.stats().canonicalWritten());
            assertEquals(
                List.of(rawPayload("raw-after-canonical-store-failure")),
                store.rawEvents.stream().map(RawWsDbEvent::rawPayload).toList()
            );
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
                () -> writer.stats().failedBatches() == 1L
                    && metrics.get(BoundedAsyncDbWriter.BATCH_FAILED_COUNTER) == 1L,
                "failed mapper batch should be counted by the worker"
            );
            assertEquals(0L, writer.stats().rawWritten());
            assertEquals(0, store.rawInsertCalls.get());
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
                () -> writer.stats().failedBatches() == 1L
                    && writer.stats().canonicalWritten() == 1L
                    && metrics.get(BoundedAsyncDbWriter.BATCH_FAILED_COUNTER) == 1L,
                "raw mapper failure should not prevent canonical writes from the worker"
            );
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
        BackendMetrics metrics = new BackendMetrics();

        try (BoundedAsyncDbWriter writer = new BoundedAsyncDbWriter(store, 4, 3, metrics)) {
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonical(canonicalDbEvent("mapped-canonical-ok")));
            assertEquals(DbOfferResult.ACCEPTED, writer.offerCanonicalEvent(canonicalEventWithoutMetadata("bad-canonical")));

            assertEventually(
                () -> writer.stats().failedBatches() == 1L
                    && writer.stats().canonicalWritten() == 1L
                    && metrics.get(BoundedAsyncDbWriter.BATCH_FAILED_COUNTER) == 1L,
                "canonical mapper failure should not prevent pre-mapped canonical writes"
            );
            assertEquals(
                List.of("mapped-canonical-ok"),
                store.canonicalEvents.stream().map(CanonicalDbEvent::eventId).toList()
            );
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

    private static SerializedCanonicalEvent serializedCanonicalEvent(String eventId) {
        return SerializedCanonicalEvent.from(canonicalEvent(eventId), new JsonCanonicalSerializer());
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

    private static void countResult(
        DbOfferResult result,
        AtomicLong accepted,
        AtomicLong dropped,
        AtomicLong disabled
    ) {
        switch (result) {
            case ACCEPTED -> accepted.incrementAndGet();
            case DROPPED_FULL -> dropped.incrementAndGet();
            case DISABLED -> disabled.incrementAndGet();
        }
    }

    private static final class RecordingStore implements AcceptedEventStore {
        private final AtomicBoolean blockRaw = new AtomicBoolean();
        private final AtomicBoolean blockCanonical = new AtomicBoolean();
        private final AtomicBoolean failRaw = new AtomicBoolean();
        private final AtomicBoolean failCanonical = new AtomicBoolean();
        private final CountDownLatch rawBatchStarted = new CountDownLatch(1);
        private final CountDownLatch canonicalBatchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRaw = new CountDownLatch(1);
        private final CountDownLatch releaseCanonical = new CountDownLatch(1);
        private final List<RawWsDbEvent> rawEvents = new CopyOnWriteArrayList<>();
        private final List<CanonicalDbEvent> canonicalEvents = new CopyOnWriteArrayList<>();
        private final AtomicInteger rawInsertCalls = new AtomicInteger();
        private final AtomicInteger canonicalInsertCalls = new AtomicInteger();

        @Override
        public void insertRawBatch(List<RawWsDbEvent> events) throws Exception {
            rawInsertCalls.incrementAndGet();
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
        public void insertCanonicalBatch(List<CanonicalDbEvent> events) throws Exception {
            canonicalInsertCalls.incrementAndGet();
            canonicalBatchStarted.countDown();
            if (blockCanonical.get()) {
                assertTrue(releaseCanonical.await(5, TimeUnit.SECONDS), "test did not release canonical insert");
            }
            if (failCanonical.get()) {
                throw new IllegalStateException("canonical insert failed");
            }
            canonicalEvents.addAll(events);
        }
    }

    private static final class ThrowingJsonCanonicalSerializer extends JsonCanonicalSerializer {
        @Override
        public byte[] toBytes(CanonicalEvent event) {
            throw new IllegalStateException("serialized path must not call toBytes");
        }

        @Override
        public String toJson(CanonicalEvent event) {
            throw new IllegalStateException("serialized path must not call toJson");
        }
    }
}
