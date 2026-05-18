package edu.illinois.group8.storage.db;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.metrics.BackendMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BoundedAsyncDbWriter implements AsyncDbWriter {
    public static final String RAW_ACCEPTED_COUNTER = "db_raw_events_accepted_total";
    public static final String RAW_DROPPED_COUNTER = "db_raw_events_dropped_total";
    public static final String RAW_WRITTEN_COUNTER = "db_raw_events_written_total";
    public static final String CANONICAL_ACCEPTED_COUNTER = "db_canonical_events_accepted_total";
    public static final String CANONICAL_DROPPED_COUNTER = "db_canonical_events_dropped_total";
    public static final String CANONICAL_WRITTEN_COUNTER = "db_canonical_events_written_total";
    public static final String BATCH_FAILED_COUNTER = "db_writer_batch_failed_total";
    public static final String QUEUE_DEPTH_GAUGE = "db_writer_queue_depth";

    private static final long CLOSE_TIMEOUT_MS = 2_000L;
    private static final long POLL_TIMEOUT_MS = 100L;

    private final AcceptedEventStore store;
    private final RawWsDbEventMapper rawMapper;
    private final CanonicalDbEventMapper canonicalMapper;
    private final BlockingQueue<WriteRequest> queue;
    private final int queueCapacity;
    private final int batchSize;
    private final BackendMetrics metrics;
    private final Thread worker;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong rawAccepted = new AtomicLong();
    private final AtomicLong rawDropped = new AtomicLong();
    private final AtomicLong rawWritten = new AtomicLong();
    private final AtomicLong canonicalAccepted = new AtomicLong();
    private final AtomicLong canonicalDropped = new AtomicLong();
    private final AtomicLong canonicalWritten = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();

    public BoundedAsyncDbWriter(AcceptedEventStore store, int queueCapacity, int batchSize) {
        this(store, queueCapacity, batchSize, new BackendMetrics());
    }

    public BoundedAsyncDbWriter(
        AcceptedEventStore store,
        int queueCapacity,
        int batchSize,
        BackendMetrics metrics
    ) {
        this(store, queueCapacity, batchSize, metrics, new RawWsDbEventMapper());
    }

    BoundedAsyncDbWriter(
        AcceptedEventStore store,
        int queueCapacity,
        int batchSize,
        BackendMetrics metrics,
        RawWsDbEventMapper rawMapper
    ) {
        this(store, queueCapacity, batchSize, metrics, rawMapper, new CanonicalDbEventMapper());
    }

    BoundedAsyncDbWriter(
        AcceptedEventStore store,
        int queueCapacity,
        int batchSize,
        BackendMetrics metrics,
        RawWsDbEventMapper rawMapper,
        CanonicalDbEventMapper canonicalMapper
    ) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive.");
        }
        this.store = Objects.requireNonNull(store, "store");
        this.rawMapper = Objects.requireNonNull(rawMapper, "rawMapper");
        this.canonicalMapper = Objects.requireNonNull(canonicalMapper, "canonicalMapper");
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.worker = new Thread(this::runWorker, "bounded-async-db-writer");
        this.worker.setDaemon(true);
        updateQueueDepthGauge();
        this.worker.start();
    }

    @Override
    public DbOfferResult offerRaw(RawWsDbEventInput input) {
        Objects.requireNonNull(input, "input");
        synchronized (lifecycleLock) {
            if (!accepting.get()) {
                return DbOfferResult.DISABLED;
            }
            if (!queue.offer(new RawWrite(input))) {
                rawDropped.incrementAndGet();
                metrics.increment(RAW_DROPPED_COUNTER);
                updateQueueDepthGauge();
                return DbOfferResult.DROPPED_FULL;
            }
            rawAccepted.incrementAndGet();
            metrics.increment(RAW_ACCEPTED_COUNTER);
            updateQueueDepthGauge();
            return DbOfferResult.ACCEPTED;
        }
    }

    @Override
    public DbOfferResult offerCanonical(CanonicalDbEvent event) {
        Objects.requireNonNull(event, "event");
        return offerCanonicalRequest(new CanonicalWrite(event));
    }

    @Override
    public DbOfferResult offerCanonicalEvent(CanonicalEvent event) {
        Objects.requireNonNull(event, "event");
        return offerCanonicalRequest(new CanonicalEventWrite(event));
    }

    private DbOfferResult offerCanonicalRequest(WriteRequest request) {
        synchronized (lifecycleLock) {
            if (!accepting.get()) {
                return DbOfferResult.DISABLED;
            }
            if (!queue.offer(request)) {
                canonicalDropped.incrementAndGet();
                metrics.increment(CANONICAL_DROPPED_COUNTER);
                updateQueueDepthGauge();
                return DbOfferResult.DROPPED_FULL;
            }
            canonicalAccepted.incrementAndGet();
            metrics.increment(CANONICAL_ACCEPTED_COUNTER);
            updateQueueDepthGauge();
            return DbOfferResult.ACCEPTED;
        }
    }

    @Override
    public DbWriterStats stats() {
        return new DbWriterStats(
            rawAccepted.get(),
            rawDropped.get(),
            rawWritten.get(),
            canonicalAccepted.get(),
            canonicalDropped.get(),
            canonicalWritten.get(),
            failedBatches.get(),
            queue.size(),
            queueCapacity
        );
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            accepting.set(false);
        }
        worker.interrupt();
        try {
            worker.join(CLOSE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runWorker() {
        while (accepting.get() || !queue.isEmpty()) {
            try {
                WriteRequest first = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<WriteRequest> requests = new ArrayList<>(batchSize);
                requests.add(first);
                queue.drainTo(requests, batchSize - 1);
                writeRequests(requests);
                updateQueueDepthGauge();
            } catch (InterruptedException e) {
                if (accepting.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException e) {
                countFailedBatch(e);
            }
        }
        updateQueueDepthGauge();
    }

    private void writeRequests(List<WriteRequest> requests) {
        List<RawWsDbEventInput> rawInputs = new ArrayList<>();
        List<CanonicalDbEvent> mappedCanonicalEvents = new ArrayList<>();
        List<CanonicalEvent> canonicalEvents = new ArrayList<>();
        for (WriteRequest request : requests) {
            if (request instanceof RawWrite rawWrite) {
                rawInputs.add(rawWrite.input());
            } else if (request instanceof CanonicalWrite canonicalWrite) {
                mappedCanonicalEvents.add(canonicalWrite.event());
            } else if (request instanceof CanonicalEventWrite canonicalEventWrite) {
                canonicalEvents.add(canonicalEventWrite.event());
            }
        }
        if (!rawInputs.isEmpty()) {
            writeRawBatch(rawInputs);
        }
        if (!mappedCanonicalEvents.isEmpty() || !canonicalEvents.isEmpty()) {
            writeCanonicalBatch(mappedCanonicalEvents, canonicalEvents);
        }
    }

    private void writeRawBatch(List<RawWsDbEventInput> rawInputs) {
        try {
            List<RawWsDbEvent> rawEvents = new ArrayList<>(rawInputs.size());
            for (RawWsDbEventInput rawInput : rawInputs) {
                rawEvents.add(rawMapper.toDbEvent(rawInput));
            }
            store.insertRawBatch(List.copyOf(rawEvents));
            rawWritten.addAndGet(rawEvents.size());
            metrics.add(RAW_WRITTEN_COUNTER, rawEvents.size());
        } catch (Exception e) {
            countFailedBatch(e);
        }
    }

    private void writeCanonicalBatch(
        List<CanonicalDbEvent> mappedCanonicalEvents,
        List<CanonicalEvent> canonicalEvents
    ) {
        if (!mappedCanonicalEvents.isEmpty()) {
            writeMappedCanonicalBatch(mappedCanonicalEvents);
        }
        if (!canonicalEvents.isEmpty()) {
            writeCanonicalEventBatch(canonicalEvents);
        }
    }

    private void writeMappedCanonicalBatch(List<CanonicalDbEvent> canonicalEvents) {
        try {
            store.insertCanonicalBatch(List.copyOf(canonicalEvents));
            canonicalWritten.addAndGet(canonicalEvents.size());
            metrics.add(CANONICAL_WRITTEN_COUNTER, canonicalEvents.size());
        } catch (Exception e) {
            countFailedBatch(e);
        }
    }

    private void writeCanonicalEventBatch(List<CanonicalEvent> canonicalEvents) {
        try {
            List<CanonicalDbEvent> dbEvents = new ArrayList<>(canonicalEvents.size());
            for (CanonicalEvent canonicalEvent : canonicalEvents) {
                dbEvents.add(canonicalMapper.toDbEvent(canonicalEvent));
            }
            writeMappedCanonicalBatch(dbEvents);
        } catch (Exception e) {
            countFailedBatch(e);
        }
    }

    private void countFailedBatch(Exception e) {
        failedBatches.incrementAndGet();
        metrics.increment(BATCH_FAILED_COUNTER);
        System.err.println("Async DB writer failed to write batch: " + e.getMessage());
    }

    private void updateQueueDepthGauge() {
        metrics.setGauge(QUEUE_DEPTH_GAUGE, queue.size());
    }

    private interface WriteRequest {
    }

    private record RawWrite(RawWsDbEventInput input) implements WriteRequest {
    }

    private record CanonicalWrite(CanonicalDbEvent event) implements WriteRequest {
    }

    private record CanonicalEventWrite(CanonicalEvent event) implements WriteRequest {
    }
}
