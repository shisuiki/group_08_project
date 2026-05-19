package edu.illinois.group8.storage.db;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.SerializedCanonicalEvent;
import edu.illinois.group8.metrics.BackendMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final BackendMetrics.Counter rawAcceptedCounter;
    private final BackendMetrics.Counter rawDroppedCounter;
    private final BackendMetrics.Counter rawWrittenCounter;
    private final BackendMetrics.Counter canonicalAcceptedCounter;
    private final BackendMetrics.Counter canonicalDroppedCounter;
    private final BackendMetrics.Counter canonicalWrittenCounter;
    private final BackendMetrics.Counter batchFailedCounter;
    private final Thread worker;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicInteger activeOffers = new AtomicInteger();
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
        this.rawAcceptedCounter = this.metrics.counter(RAW_ACCEPTED_COUNTER);
        this.rawDroppedCounter = this.metrics.counter(RAW_DROPPED_COUNTER);
        this.rawWrittenCounter = this.metrics.counter(RAW_WRITTEN_COUNTER);
        this.canonicalAcceptedCounter = this.metrics.counter(CANONICAL_ACCEPTED_COUNTER);
        this.canonicalDroppedCounter = this.metrics.counter(CANONICAL_DROPPED_COUNTER);
        this.canonicalWrittenCounter = this.metrics.counter(CANONICAL_WRITTEN_COUNTER);
        this.batchFailedCounter = this.metrics.counter(BATCH_FAILED_COUNTER);
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.worker = new Thread(this::runWorker, "bounded-async-db-writer");
        this.worker.setDaemon(true);
        updateQueueDepthGauge();
        this.worker.start();
    }

    @Override
    public DbOfferResult offerRaw(RawWsDbEventInput input) {
        Objects.requireNonNull(input, "input");
        if (!accepting.get()) {
            return DbOfferResult.DISABLED;
        }
        activeOffers.incrementAndGet();
        try {
            if (!accepting.get()) {
                return DbOfferResult.DISABLED;
            }
            if (!queue.offer(new RawWrite(input))) {
                rawDropped.incrementAndGet();
                rawDroppedCounter.increment();
                updateQueueDepthGauge();
                return DbOfferResult.DROPPED_FULL;
            }
            rawAccepted.incrementAndGet();
            rawAcceptedCounter.increment();
            return DbOfferResult.ACCEPTED;
        } finally {
            activeOffers.decrementAndGet();
        }
    }

    @Override
    public DbOfferResult offerCanonical(CanonicalDbEvent event) {
        Objects.requireNonNull(event, "event");
        if (!accepting.get()) {
            return DbOfferResult.DISABLED;
        }
        return offerCanonicalRequest(new CanonicalWrite(event));
    }

    @Override
    public DbOfferResult offerCanonicalEvent(CanonicalEvent event) {
        Objects.requireNonNull(event, "event");
        if (!accepting.get()) {
            return DbOfferResult.DISABLED;
        }
        return offerCanonicalRequest(new CanonicalEventWrite(event));
    }

    @Override
    public DbOfferResult offerSerializedCanonicalEvent(SerializedCanonicalEvent event) {
        Objects.requireNonNull(event, "event");
        if (!accepting.get()) {
            return DbOfferResult.DISABLED;
        }
        return offerCanonicalRequest(new SerializedCanonicalEventWrite(event));
    }

    private DbOfferResult offerCanonicalRequest(WriteRequest request) {
        if (!accepting.get()) {
            return DbOfferResult.DISABLED;
        }
        activeOffers.incrementAndGet();
        try {
            if (!accepting.get()) {
                return DbOfferResult.DISABLED;
            }
            if (!queue.offer(request)) {
                canonicalDropped.incrementAndGet();
                canonicalDroppedCounter.increment();
                updateQueueDepthGauge();
                return DbOfferResult.DROPPED_FULL;
            }
            canonicalAccepted.incrementAndGet();
            canonicalAcceptedCounter.increment();
            return DbOfferResult.ACCEPTED;
        } finally {
            activeOffers.decrementAndGet();
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
        accepting.set(false);
        // Let in-flight store calls finish; idle workers wake via poll timeout.
        try {
            worker.join(CLOSE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runWorker() {
        while (shouldDrain()) {
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

    private boolean shouldDrain() {
        return accepting.get() || activeOffers.get() > 0 || !queue.isEmpty();
    }

    private void writeRequests(List<WriteRequest> requests) {
        List<RawWsDbEventInput> rawInputs = new ArrayList<>();
        List<CanonicalDbEvent> mappedCanonicalEvents = new ArrayList<>();
        List<CanonicalEvent> canonicalEvents = new ArrayList<>();
        List<SerializedCanonicalEvent> serializedCanonicalEvents = new ArrayList<>();
        for (WriteRequest request : requests) {
            if (request instanceof RawWrite rawWrite) {
                rawInputs.add(rawWrite.input());
            } else if (request instanceof CanonicalWrite canonicalWrite) {
                mappedCanonicalEvents.add(canonicalWrite.event());
            } else if (request instanceof CanonicalEventWrite canonicalEventWrite) {
                canonicalEvents.add(canonicalEventWrite.event());
            } else if (request instanceof SerializedCanonicalEventWrite serializedCanonicalEventWrite) {
                serializedCanonicalEvents.add(serializedCanonicalEventWrite.event());
            }
        }
        if (!rawInputs.isEmpty()) {
            writeRawBatch(rawInputs);
        }
        if (!mappedCanonicalEvents.isEmpty() || !canonicalEvents.isEmpty() || !serializedCanonicalEvents.isEmpty()) {
            writeCanonicalBatch(mappedCanonicalEvents, canonicalEvents, serializedCanonicalEvents);
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
            rawWrittenCounter.add(rawEvents.size());
        } catch (Exception e) {
            countFailedBatch(e);
        }
    }

    private void writeCanonicalBatch(
        List<CanonicalDbEvent> mappedCanonicalEvents,
        List<CanonicalEvent> canonicalEvents,
        List<SerializedCanonicalEvent> serializedCanonicalEvents
    ) {
        if (!mappedCanonicalEvents.isEmpty()) {
            writeMappedCanonicalBatch(mappedCanonicalEvents);
        }
        if (!canonicalEvents.isEmpty()) {
            writeCanonicalEventBatch(canonicalEvents);
        }
        if (!serializedCanonicalEvents.isEmpty()) {
            writeSerializedCanonicalEventBatch(serializedCanonicalEvents);
        }
    }

    private void writeMappedCanonicalBatch(List<CanonicalDbEvent> canonicalEvents) {
        try {
            store.insertCanonicalBatch(List.copyOf(canonicalEvents));
            canonicalWritten.addAndGet(canonicalEvents.size());
            canonicalWrittenCounter.add(canonicalEvents.size());
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

    private void writeSerializedCanonicalEventBatch(List<SerializedCanonicalEvent> canonicalEvents) {
        try {
            List<CanonicalDbEvent> dbEvents = new ArrayList<>(canonicalEvents.size());
            for (SerializedCanonicalEvent canonicalEvent : canonicalEvents) {
                dbEvents.add(canonicalMapper.toDbEvent(canonicalEvent));
            }
            writeMappedCanonicalBatch(dbEvents);
        } catch (Exception e) {
            countFailedBatch(e);
        }
    }

    private void countFailedBatch(Exception e) {
        failedBatches.incrementAndGet();
        batchFailedCounter.increment();
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

    private record SerializedCanonicalEventWrite(SerializedCanonicalEvent event) implements WriteRequest {
    }
}
