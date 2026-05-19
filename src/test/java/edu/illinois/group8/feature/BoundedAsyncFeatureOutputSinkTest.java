package edu.illinois.group8.feature;

import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.FeatureOutputDbEvent;
import edu.illinois.group8.storage.db.FeatureOutputStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedAsyncFeatureOutputSinkTest {
    @Test
    void nonBlockingOfferDropsWhenQueueIsFull() {
        BackendMetrics metrics = new BackendMetrics();
        CapturingStore store = new CapturingStore();
        BoundedAsyncFeatureOutputSink sink = new BoundedAsyncFeatureOutputSink(
            store,
            new FeatureOutputDbEventMapper(),
            metrics,
            1,
            10,
            10L,
            false
        );

        sink.write(output("source-1"));
        sink.write(output("source-2"));

        assertEquals(1L, metrics.get("featureplant_db_output_events_total", labels("accepted")));
        assertEquals(1L, metrics.get("featureplant_db_output_events_total", labels("dropped_full")));
        assertEquals(1, sink.queueDepth());
        assertTrue(metrics.prometheusText().contains("featureplant_db_output_queue_depth{service=\"featureplant\"} 1\n"));
    }

    @Test
    void workerWritesAcceptedOutputsInBatches() throws Exception {
        BackendMetrics metrics = new BackendMetrics();
        CapturingStore store = new CapturingStore();
        try (BoundedAsyncFeatureOutputSink sink = new BoundedAsyncFeatureOutputSink(
            store,
            metrics,
            10,
            3,
            1000L
        )) {
            sink.write(output("source-1"));
            sink.write(output("source-2"));
            sink.write(output("source-3"));

            waitUntil(() -> store.totalWritten() == 3);
        }

        assertEquals(3L, metrics.get("featureplant_db_output_events_total", labels("accepted")));
        assertEquals(3L, metrics.get("featureplant_db_output_events_total", labels("written")));
        assertEquals(0L, metrics.get("featureplant_db_output_events_total", labels("failed")));
        assertTrue(store.batchCalls() >= 1);
        assertEquals(List.of("source-1", "source-2", "source-3"), store.sourceEventIds());
    }

    @Test
    void closeDrainsAcceptedQueueWithinTimeout() {
        BackendMetrics metrics = new BackendMetrics();
        CapturingStore store = new CapturingStore();
        BoundedAsyncFeatureOutputSink sink = new BoundedAsyncFeatureOutputSink(
            store,
            metrics,
            10,
            10,
            1000L
        );

        sink.write(output("source-1"));
        sink.write(output("source-2"));
        sink.close();

        assertEquals(2, store.totalWritten());
        assertEquals(0, sink.queueDepth());
        assertEquals(2L, metrics.get("featureplant_db_output_events_total", labels("written")));
    }

    @Test
    void closeDoesNotWaitIndefinitelyForBlockedStore() throws Exception {
        BackendMetrics metrics = new BackendMetrics();
        BlockingStore store = new BlockingStore();
        BoundedAsyncFeatureOutputSink sink = new BoundedAsyncFeatureOutputSink(
            store,
            metrics,
            10,
            10,
            25L
        );

        sink.write(output("source-1"));
        assertTrue(store.started.await(2, TimeUnit.SECONDS));

        long startNs = System.nanoTime();
        sink.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        store.release.countDown();

        assertTrue(elapsedMs < 500L, "close waited too long: " + elapsedMs + "ms");
    }

    @Test
    void failedBatchIsCountedWithoutEscapingWrite() throws Exception {
        BackendMetrics metrics = new BackendMetrics();
        FailingStore store = new FailingStore();
        try (BoundedAsyncFeatureOutputSink sink = new BoundedAsyncFeatureOutputSink(
            store,
            metrics,
            10,
            10,
            1000L
        )) {
            sink.write(output("source-1"));
            waitUntil(() -> metrics.get("featureplant_db_output_events_total", labels("failed")) == 1L);
        }

        assertEquals(1L, metrics.get("featureplant_db_output_events_total", labels("accepted")));
        assertEquals(0L, metrics.get("featureplant_db_output_events_total", labels("written")));
        assertEquals(1L, metrics.get("featureplant_db_output_events_total", labels("failed")));
    }

    private static FeatureOutput output(String sourceEventId) {
        return new FeatureOutput(
            "feature.bbo",
            "derived.top_of_book",
            "M1",
            100L,
            sourceEventId,
            Map.of("midpoint_micros", 123L)
        );
    }

    private static Map<String, String> labels(String result) {
        return BackendMetrics.labels("service", "featureplant", "result", result);
    }

    private static void waitUntil(Condition condition) throws Exception {
        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNs) {
            if (condition.met()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(condition.met(), "condition was not met before timeout");
    }

    @FunctionalInterface
    private interface Condition {
        boolean met();
    }

    private static final class CapturingStore implements FeatureOutputStore {
        private final List<List<FeatureOutputDbEvent>> batches = new CopyOnWriteArrayList<>();

        @Override
        public void insertFeatureOutput(FeatureOutputDbEvent output) {
            insertFeatureOutputBatch(List.of(output));
        }

        @Override
        public void insertFeatureOutputBatch(List<FeatureOutputDbEvent> outputs) {
            batches.add(List.copyOf(outputs));
        }

        private int totalWritten() {
            return batches.stream().mapToInt(List::size).sum();
        }

        private int batchCalls() {
            return batches.size();
        }

        private List<String> sourceEventIds() {
            List<String> ids = new ArrayList<>();
            for (List<FeatureOutputDbEvent> batch : batches) {
                for (FeatureOutputDbEvent event : batch) {
                    ids.add(event.sourceEventId());
                }
            }
            return ids;
        }
    }

    private static final class FailingStore implements FeatureOutputStore {
        @Override
        public void insertFeatureOutput(FeatureOutputDbEvent output) throws Exception {
            throw new Exception("db unavailable");
        }

        @Override
        public void insertFeatureOutputBatch(List<FeatureOutputDbEvent> outputs) throws Exception {
            throw new Exception("db unavailable");
        }
    }

    private static final class BlockingStore implements FeatureOutputStore {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void insertFeatureOutput(FeatureOutputDbEvent output) throws Exception {
            insertFeatureOutputBatch(List.of(output));
        }

        @Override
        public void insertFeatureOutputBatch(List<FeatureOutputDbEvent> outputs) throws Exception {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
        }
    }
}
