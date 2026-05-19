package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.storage.db.FeatureOutputCursor;
import edu.illinois.group8.storage.db.FeatureOutputReadRequest;
import edu.illinois.group8.storage.db.FeatureOutputRow;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class FeatureOutputRefreshService implements AutoCloseable {
    @FunctionalInterface
    interface RowReader {
        List<FeatureOutputRow> read(FeatureOutputReadRequest request);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final Comparator<FeatureOutputRow> CURSOR_ORDER = Comparator
        .comparing(FeatureOutputRow::createdAt)
        .thenComparing(FeatureOutputRow::featureEventId);

    private final FrontendAdapterConfig config;
    private final FrontendFeatureStore store;
    private final RowReader reader;
    private final Sleeper sleeper;
    private final List<String> featureNames;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<FeatureOutputRefreshStatus> status;
    private volatile FeatureOutputCursor cursor;
    private volatile Thread worker;

    FeatureOutputRefreshService(FrontendAdapterConfig config, FrontendFeatureStore store, RowReader reader) {
        this(config, store, reader, Thread::sleep);
    }

    FeatureOutputRefreshService(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        RowReader reader,
        Sleeper sleeper
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.featureNames = FrontendAdapterMain.resolveFeatureNames(config.moduleNames());
        this.status = new AtomicReference<>(new FeatureOutputRefreshStatus(
            config.featureOutputRefreshEnabled(),
            false,
            null,
            null,
            null,
            0,
            0L,
            0L
        ));
    }

    int seedOnce() {
        List<FeatureOutputRow> rows = reader.read(FrontendAdapterMain.featureOutputReadRequest(config));
        acceptRowsOldestFirst(rows);
        updateCursor(rows);
        recordSuccess(rows.size());
        return rows.size();
    }

    int refreshOnce() {
        try {
            List<FeatureOutputRow> rows = reader.read(FeatureOutputReadRequest.afterCreatedAt(
                featureNames,
                cursor,
                config.featureOutputRefreshMaxRows()
            ));
            for (FeatureOutputRow row : rows) {
                store.accept(row.output());
            }
            updateCursor(rows);
            recordSuccess(rows.size());
            return rows.size();
        } catch (RuntimeException e) {
            recordError(e);
            return 0;
        }
    }

    void start() {
        if (!config.featureOutputRefreshEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        status.updateAndGet(current -> current.withRunning(true));
        Thread thread = new Thread(this::runLoop, "frontend-feature-output-refresh");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    FeatureOutputRefreshStatus status() {
        return status.get();
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            refreshOnce();
            try {
                sleeper.sleep(config.featureOutputRefreshIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running.set(false);
        status.updateAndGet(current -> current.withRunning(false));
    }

    @Override
    public void close() {
        running.set(false);
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
            if (Thread.currentThread() != thread) {
                try {
                    thread.join(2_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        status.updateAndGet(current -> current.withRunning(false));
    }

    private void acceptRowsOldestFirst(List<FeatureOutputRow> rows) {
        for (int index = rows.size() - 1; index >= 0; index--) {
            FeatureOutput output = rows.get(index).output();
            store.accept(output);
        }
    }

    private void updateCursor(List<FeatureOutputRow> rows) {
        rows.stream()
            .max(CURSOR_ORDER)
            .map(FeatureOutputRow::cursor)
            .ifPresent(value -> cursor = value);
    }

    private void recordSuccess(int rowCount) {
        status.updateAndGet(current -> new FeatureOutputRefreshStatus(
            current.enabled(),
            running.get(),
            Instant.now(),
            current.lastErrorAt(),
            current.lastError(),
            rowCount,
            current.totalLoaded() + rowCount,
            current.refreshErrors()
        ));
    }

    private void recordError(RuntimeException e) {
        status.updateAndGet(current -> new FeatureOutputRefreshStatus(
            current.enabled(),
            running.get(),
            current.lastSuccessAt(),
            Instant.now(),
            e.getMessage(),
            current.lastRowCount(),
            current.totalLoaded(),
            current.refreshErrors() + 1L
        ));
    }
}
