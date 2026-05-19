package edu.illinois.group8.wrapper;

import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.SequenceGapEvent;
import edu.illinois.group8.metrics.BackendMetrics;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

public final class OrderBookRecoveryController {
    private static final Set<String> SNAPSHOT_REASONS = Set.of(
        "source_sequence_gap",
        "non_monotonic_source_sequence",
        "non_monotonic_orderbook_sequence",
        "crossed_book",
        "delta_before_snapshot",
        "market_paused_for_snapshot_recovery"
    );

    private final ConcurrentMap<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Set<String> inFlightMarkets = ConcurrentHashMap.newKeySet();
    private final Executor executor;
    private final int timeoutMs;
    private final OrderBookRecoveryMetrics metrics;

    public OrderBookRecoveryController(Executor executor, int timeoutMs) {
        this(executor, timeoutMs, new OrderBookRecoveryMetrics(new BackendMetrics()));
    }

    public OrderBookRecoveryController(Executor executor, int timeoutMs, OrderBookRecoveryMetrics metrics) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive.");
        }
        this.timeoutMs = timeoutMs;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.metrics.setRegisteredMarkets(registrations.size());
        this.metrics.setInflightMarkets(inFlightMarkets.size());
    }

    public void registerMarket(String marketTicker, long sid, SnapshotRequester requester) {
        String normalizedTicker = normalizedMarketTicker(marketTicker);
        if (normalizedTicker == null) {
            throw new IllegalArgumentException("marketTicker must not be null or blank.");
        }
        if (sid < 0L) {
            throw new IllegalArgumentException("sid must not be negative.");
        }
        registrations.put(normalizedTicker, new Registration(sid, Objects.requireNonNull(requester, "requester")));
        metrics.setRegisteredMarkets(registrations.size());
    }

    public void unregisterMarket(String marketTicker) {
        String normalizedTicker = normalizedMarketTicker(marketTicker);
        if (normalizedTicker == null) {
            return;
        }
        registrations.remove(normalizedTicker);
        inFlightMarkets.remove(normalizedTicker);
        metrics.setRegisteredMarkets(registrations.size());
        metrics.setInflightMarkets(inFlightMarkets.size());
    }

    public RequestStatus handleGap(SequenceGapEvent gap) {
        if (gap == null || !SNAPSHOT_REASONS.contains(gap.reason())) {
            return decision(RequestStatus.SKIPPED_UNSUPPORTED_REASON);
        }
        EventMetadata metadata = gap.metadata();
        if (metadata == null) {
            return decision(RequestStatus.SKIPPED_MISSING_METADATA);
        }
        String marketTicker = normalizedMarketTicker(metadata.marketTicker());
        if (marketTicker == null) {
            return decision(RequestStatus.SKIPPED_MISSING_METADATA);
        }
        Registration registration = registrations.get(marketTicker);
        if (registration == null) {
            return decision(RequestStatus.SKIPPED_UNKNOWN_MARKET);
        }
        if (!inFlightMarkets.add(marketTicker)) {
            return decision(RequestStatus.SKIPPED_IN_FLIGHT);
        }
        metrics.setInflightMarkets(inFlightMarkets.size());

        try {
            executor.execute(() -> requestSnapshot(marketTicker, registration));
        } catch (RuntimeException exc) {
            inFlightMarkets.remove(marketTicker);
            metrics.setInflightMarkets(inFlightMarkets.size());
            throw exc;
        }
        return decision(RequestStatus.REQUEST_SCHEDULED);
    }

    private void requestSnapshot(String marketTicker, Registration registration) {
        try {
            registration.requester().requestSnapshotAndAwaitOk(
                registration.sid(),
                new String[] {marketTicker},
                timeoutMs
            );
            metrics.recordSnapshotRequestSuccess();
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            metrics.recordSnapshotRequestInterrupted();
        } catch (RuntimeException exc) {
            // Recovery requests are best-effort; a failed request must not poison future gaps.
            metrics.recordSnapshotRequestRuntimeException();
        } finally {
            inFlightMarkets.remove(marketTicker);
            metrics.setInflightMarkets(inFlightMarkets.size());
        }
    }

    private RequestStatus decision(RequestStatus status) {
        metrics.recordSnapshotRequestDecision(status);
        return status;
    }

    private static String normalizedMarketTicker(String marketTicker) {
        if (marketTicker == null || marketTicker.isBlank()) {
            return null;
        }
        return marketTicker;
    }

    public enum RequestStatus {
        REQUEST_SCHEDULED,
        SKIPPED_IN_FLIGHT,
        SKIPPED_UNKNOWN_MARKET,
        SKIPPED_UNSUPPORTED_REASON,
        SKIPPED_MISSING_METADATA
    }

    @FunctionalInterface
    public interface SnapshotRequester {
        void requestSnapshotAndAwaitOk(long sid, String[] marketTickers, int timeoutMs) throws InterruptedException;
    }

    private record Registration(long sid, SnapshotRequester requester) {
    }
}
