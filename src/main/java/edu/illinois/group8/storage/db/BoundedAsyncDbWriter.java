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
    public static final String RAW_QUEUE_DEPTH_GAUGE = "db_writer_raw_queue_depth";
    public static final String CANONICAL_QUEUE_DEPTH_GAUGE = "db_writer_canonical_queue_depth";

    private static final long CLOSE_TIMEOUT_MS = 2_000L;
    private static final long POLL_TIMEOUT_MS = 100L;

    private final AcceptedEventStore store;
    private final RawWsDbEventMapper rawMapper;
    private final CanonicalDbEventMapper canonicalMapper;
    private final BlockingQueue<RawWrite> rawQueue;
    private final BlockingQueue<CanonicalWriteRequest> canonicalQueue;
    private final int queueCapacity;
    private final int rawQueueCapacity;
    private final int canonicalQueueCapacity;
    private final int batchSize;
    private final BackendMetrics metrics;
    private final BackendMetrics.Counter rawAcceptedCounter;
    private final BackendMetrics.Counter rawDroppedCounter;
    private final BackendMetrics.Counter rawWrittenCounter;
    private final BackendMetrics.Counter canonicalAcceptedCounter;
    private final BackendMetrics.Counter canonicalDroppedCounter;
    private final BackendMetrics.Counter canonicalWrittenCounter;
    private final BackendMetrics.Counter batchFailedCounter;
    private final Thread rawWorker;
    private final Thread canonicalWorker;
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
        this.canonicalQueueCapacity = Math.max(1, (queueCapacity + 1) / 2);
        this.rawQueueCapacity = Math.max(1, queueCapacity - canonicalQueueCapacity);
        this.queueCapacity = rawQueueCapacity + canonicalQueueCapacity;
        this.batchSize = batchSize;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.rawAcceptedCounter = this.metrics.counter(RAW_ACCEPTED_COUNTER);
        this.rawDroppedCounter = this.metrics.counter(RAW_DROPPED_COUNTER);
        this.rawWrittenCounter = this.metrics.counter(RAW_WRITTEN_COUNTER);
        this.canonicalAcceptedCounter = this.metrics.counter(CANONICAL_ACCEPTED_COUNTER);
        this.canonicalDroppedCounter = this.metrics.counter(CANONICAL_DROPPED_COUNTER);
        this.canonicalWrittenCounter = this.metrics.counter(CANONICAL_WRITTEN_COUNTER);
        this.batchFailedCounter = this.metrics.counter(BATCH_FAILED_COUNTER);
        this.rawQueue = new ArrayBlockingQueue<>(rawQueueCapacity);
        this.canonicalQueue = new ArrayBlockingQueue<>(canonicalQueueCapacity);
        this.rawWorker = new Thread(this::runRawWorker, "bounded-async-db-writer-raw");
        this.canonicalWorker = new Thread(this::runCanonicalWorker, "bounded-async-db-writer-canonical");
        this.rawWorker.setDaemon(true);
        this.canonicalWorker.setDaemon(true);
        updateQueueDepthGauge();
        this.rawWorker.start();
        this.canonicalWorker.start();
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
            if (!rawQueue.offer(new RawWrite(input))) {
                rawDropped.incrementAndGet();
                rawDroppedCounter.increment();
                updateQueueDepthGauge();
                return DbOfferResult.DROPPED_FULL;
            }
            rawAccepted.incrementAndGet();
            rawAcceptedCounter.increment();
            updateQueueDepthGauge();
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

    private DbOfferResult offerCanonicalRequest(CanonicalWriteRequest request) {
        if (!accepting.get()) {
            return DbOfferResult.DISABLED;
        }
        activeOffers.incrementAndGet();
        try {
            if (!accepting.get()) {
                return DbOfferResult.DISABLED;
            }
            if (!canonicalQueue.offer(request)) {
                canonicalDropped.incrementAndGet();
                canonicalDroppedCounter.increment();
                updateQueueDepthGauge();
                return DbOfferResult.DROPPED_FULL;
            }
            canonicalAccepted.incrementAndGet();
            canonicalAcceptedCounter.increment();
            updateQueueDepthGauge();
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
            rawQueue.size() + canonicalQueue.size(),
            queueCapacity
        );
    }

    @Override
    public void close() {
        accepting.set(false);
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_TIMEOUT_MS);
        joinWorker(rawWorker, deadlineNs);
        joinWorker(canonicalWorker, deadlineNs);
    }

    private void joinWorker(Thread worker, long deadlineNs) {
        long remainingNs = deadlineNs - System.nanoTime();
        if (remainingNs <= 0L) {
            return;
        }
        try {
            worker.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runRawWorker() {
        while (shouldDrain(rawQueue)) {
            try {
                RawWrite first = rawQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<RawWrite> requests = new ArrayList<>(batchSize);
                requests.add(first);
                rawQueue.drainTo(requests, batchSize - 1);
                updateQueueDepthGauge();
                writeRawRequests(requests);
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

    private void runCanonicalWorker() {
        while (shouldDrain(canonicalQueue)) {
            try {
                CanonicalWriteRequest first = canonicalQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<CanonicalWriteRequest> requests = new ArrayList<>(batchSize);
                requests.add(first);
                canonicalQueue.drainTo(requests, batchSize - 1);
                updateQueueDepthGauge();
                writeCanonicalRequests(requests);
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

    private boolean shouldDrain(BlockingQueue<?> queue) {
        return accepting.get() || activeOffers.get() > 0 || !queue.isEmpty();
    }

    private void writeRawRequests(List<RawWrite> requests) {
        List<RawWsDbEventInput> rawInputs = new ArrayList<>(requests.size());
        for (RawWrite request : requests) {
            rawInputs.add(request.input());
        }
        writeRawBatch(rawInputs);
    }

    private void writeCanonicalRequests(List<CanonicalWriteRequest> requests) {
        List<CanonicalDbEvent> mappedCanonicalEvents = new ArrayList<>();
        List<CanonicalEvent> canonicalEvents = new ArrayList<>();
        List<SerializedCanonicalEvent> serializedCanonicalEvents = new ArrayList<>();
        for (CanonicalWriteRequest request : requests) {
            if (request instanceof CanonicalWrite canonicalWrite) {
                mappedCanonicalEvents.add(canonicalWrite.event());
            } else if (request instanceof CanonicalEventWrite canonicalEventWrite) {
                canonicalEvents.add(canonicalEventWrite.event());
            } else if (request instanceof SerializedCanonicalEventWrite serializedCanonicalEventWrite) {
                serializedCanonicalEvents.add(serializedCanonicalEventWrite.event());
            }
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
        int rawDepth = rawQueue.size();
        int canonicalDepth = canonicalQueue.size();
        metrics.setGauge(RAW_QUEUE_DEPTH_GAUGE, rawDepth);
        metrics.setGauge(CANONICAL_QUEUE_DEPTH_GAUGE, canonicalDepth);
        metrics.setGauge(QUEUE_DEPTH_GAUGE, rawDepth + canonicalDepth);
    }

    private interface CanonicalWriteRequest {
    }

    private record RawWrite(RawWsDbEventInput input) {
    }

    private record CanonicalWrite(CanonicalDbEvent event) implements CanonicalWriteRequest {
    }

    private record CanonicalEventWrite(CanonicalEvent event) implements CanonicalWriteRequest {
    }

    private record SerializedCanonicalEventWrite(SerializedCanonicalEvent event) implements CanonicalWriteRequest {
    }
}
