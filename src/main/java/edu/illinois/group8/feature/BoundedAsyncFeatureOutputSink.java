package edu.illinois.group8.feature;

import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.FeatureOutputDbEvent;
import edu.illinois.group8.storage.db.FeatureOutputStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BoundedAsyncFeatureOutputSink implements FeatureOutputSink {
    public static final int DEFAULT_QUEUE_CAPACITY = 8192;
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int DEFAULT_CLOSE_TIMEOUT_MS = 5000;

    private static final int WORKER_POLL_TIMEOUT_MS = 10;

    private final FeatureOutputStore store;
    private final FeatureOutputDbEventMapper mapper;
    private final ArrayBlockingQueue<FeatureOutput> queue;
    private final int batchSize;
    private final long closeTimeoutMs;
    private final BackendMetrics.Counter accepted;
    private final BackendMetrics.Counter droppedFull;
    private final BackendMetrics.Counter written;
    private final BackendMetrics.Counter failed;
    private final BackendMetrics metrics;
    private final Thread worker;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public BoundedAsyncFeatureOutputSink(
        FeatureOutputStore store,
        BackendMetrics metrics,
        int queueCapacity,
        int batchSize,
        long closeTimeoutMs
    ) {
        this(store, new FeatureOutputDbEventMapper(), metrics, queueCapacity, batchSize, closeTimeoutMs, true);
    }

    BoundedAsyncFeatureOutputSink(
        FeatureOutputStore store,
        FeatureOutputDbEventMapper mapper,
        BackendMetrics metrics,
        int queueCapacity,
        int batchSize,
        long closeTimeoutMs,
        boolean startWorker
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.batchSize = Math.max(1, batchSize);
        this.closeTimeoutMs = Math.max(0L, closeTimeoutMs);
        this.accepted = metrics.counter(
            "featureplant_db_output_events_total",
            BackendMetrics.labels("service", "featureplant", "result", "accepted")
        );
        this.droppedFull = metrics.counter(
            "featureplant_db_output_events_total",
            BackendMetrics.labels("service", "featureplant", "result", "dropped_full")
        );
        this.written = metrics.counter(
            "featureplant_db_output_events_total",
            BackendMetrics.labels("service", "featureplant", "result", "written")
        );
        this.failed = metrics.counter(
            "featureplant_db_output_events_total",
            BackendMetrics.labels("service", "featureplant", "result", "failed")
        );
        updateQueueDepthGauge();
        this.worker = startWorker ? newWorker() : null;
        if (this.worker != null) {
            this.worker.start();
        }
    }

    @Override
    public void write(FeatureOutput output) {
        if (!accepting.get()) {
            droppedFull.increment();
            updateQueueDepthGauge();
            return;
        }
        if (queue.offer(Objects.requireNonNull(output, "output"))) {
            accepted.increment();
        } else {
            droppedFull.increment();
        }
        updateQueueDepthGauge();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        accepting.set(false);
        if (worker == null) {
            updateQueueDepthGauge();
            return;
        }

        if (closeTimeoutMs > 0L) {
            try {
                worker.join(closeTimeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        updateQueueDepthGauge();
    }

    int queueDepth() {
        return queue.size();
    }

    private Thread newWorker() {
        Thread thread = new Thread(this::runWorker, "featureplant-db-output-writer");
        thread.setDaemon(true);
        return thread;
    }

    private void runWorker() {
        List<FeatureOutput> batch = new ArrayList<>(batchSize);
        while (!closed.get() || !queue.isEmpty()) {
            batch.clear();
            FeatureOutput first = pollFirst();
            if (first == null) {
                continue;
            }
            batch.add(first);
            queue.drainTo(batch, batchSize - 1);
            writeBatch(batch);
            updateQueueDepthGauge();
        }
    }

    private FeatureOutput pollFirst() {
        try {
            return queue.poll(WORKER_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return queue.poll();
        }
    }

    private void writeBatch(List<FeatureOutput> batch) {
        List<FeatureOutputDbEvent> events = new ArrayList<>(batch.size());
        for (FeatureOutput output : batch) {
            try {
                events.add(mapper.toDbEvent(output));
            } catch (RuntimeException e) {
                failed.increment();
                System.err.println("FeaturePlant DB output mapping failed: " + e.getMessage());
            }
        }
        if (events.isEmpty()) {
            return;
        }
        try {
            store.insertFeatureOutputBatch(List.copyOf(events));
            written.add(events.size());
        } catch (Exception e) {
            failed.add(events.size());
            System.err.println("FeaturePlant DB output batch failed: " + e.getMessage());
        }
    }

    private void updateQueueDepthGauge() {
        metrics.setGauge(
            "featureplant_db_output_queue_depth",
            BackendMetrics.labels("service", "featureplant"),
            queue.size()
        );
    }
}
