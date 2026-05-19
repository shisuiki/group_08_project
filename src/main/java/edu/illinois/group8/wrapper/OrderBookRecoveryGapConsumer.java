package edu.illinois.group8.wrapper;

import edu.illinois.group8.feature.CanonicalEnvelope;
import edu.illinois.group8.feature.CanonicalEnvelopeSource;
import edu.illinois.group8.metrics.BackendMetrics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class OrderBookRecoveryGapConsumer implements AutoCloseable {
    private final CanonicalEnvelopeSource source;
    private final OrderBookRecoveryGapHandler handler;
    private final int fragmentLimit;
    private final long idleSleepMs;
    private final Sleeper sleeper;
    private final OrderBookRecoveryMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public OrderBookRecoveryGapConsumer(
        CanonicalEnvelopeSource source,
        OrderBookRecoveryGapHandler handler,
        int fragmentLimit,
        long idleSleepMs
    ) {
        this(source, handler, fragmentLimit, idleSleepMs, new OrderBookRecoveryMetrics(new BackendMetrics()));
    }

    public OrderBookRecoveryGapConsumer(
        CanonicalEnvelopeSource source,
        OrderBookRecoveryGapHandler handler,
        int fragmentLimit,
        long idleSleepMs,
        OrderBookRecoveryMetrics metrics
    ) {
        this(source, handler, fragmentLimit, idleSleepMs, Thread::sleep, metrics);
    }

    OrderBookRecoveryGapConsumer(
        CanonicalEnvelopeSource source,
        OrderBookRecoveryGapHandler handler,
        int fragmentLimit,
        long idleSleepMs,
        Sleeper sleeper
    ) {
        this(source, handler, fragmentLimit, idleSleepMs, sleeper, new OrderBookRecoveryMetrics(new BackendMetrics()));
    }

    OrderBookRecoveryGapConsumer(
        CanonicalEnvelopeSource source,
        OrderBookRecoveryGapHandler handler,
        int fragmentLimit,
        long idleSleepMs,
        Sleeper sleeper,
        OrderBookRecoveryMetrics metrics
    ) {
        if (fragmentLimit < 1) {
            throw new IllegalArgumentException("fragmentLimit must be at least 1.");
        }
        if (idleSleepMs < 0L) {
            throw new IllegalArgumentException("idleSleepMs must not be negative.");
        }
        this.source = Objects.requireNonNull(source, "source");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.fragmentLimit = fragmentLimit;
        this.idleSleepMs = idleSleepMs;
    }

    public int pollOnce() {
        try {
            int fragments = source.poll(this::handleEnvelope, fragmentLimit);
            metrics.recordConsumerPoll(fragments);
            return fragments;
        } catch (RuntimeException exc) {
            metrics.recordConsumerPollError();
            throw exc;
        }
    }

    public void runUntilStopped() {
        runLoop(running::get);
    }

    void runLoop(BooleanSupplier keepRunning) {
        Objects.requireNonNull(keepRunning, "keepRunning");
        running.set(true);
        while (running.get() && keepRunning.getAsBoolean()) {
            int polled = pollOnce();
            if (polled == 0) {
                try {
                    sleeper.sleep(idleSleepMs);
                } catch (InterruptedException exc) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void stop() {
        running.set(false);
    }

    @Override
    public void close() {
        stop();
        if (closed.compareAndSet(false, true)) {
            source.close();
        }
    }

    private void handleEnvelope(CanonicalEnvelope envelope) {
        handler.handlePayload(envelope.streamName(), envelope.payload());
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
