package edu.illinois.group8.wrapper;

import edu.illinois.group8.metrics.BackendMetrics;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class OrderBookRecoveryMetrics {
    private static final String SERVICE = "wsclient";
    private static final Map<String, String> SERVICE_LABELS = Map.copyOf(BackendMetrics.labels("service", SERVICE));

    private final BackendMetrics metrics;
    private final EnumMap<OrderBookRecoveryGapHandler.Status, BackendMetrics.Counter> gapPayloadCounters;
    private final EnumMap<OrderBookRecoveryController.RequestStatus, BackendMetrics.Counter> snapshotDecisionCounters;
    private final BackendMetrics.Counter snapshotRequestSuccessCounter;
    private final BackendMetrics.Counter snapshotRequestInterruptedCounter;
    private final BackendMetrics.Counter snapshotRequestRuntimeExceptionCounter;
    private final BackendMetrics.Counter consumerPollNonEmptyCounter;
    private final BackendMetrics.Counter consumerPollErrorCounter;
    private final BackendMetrics.Counter consumerFragmentsCounter;

    public OrderBookRecoveryMetrics(BackendMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.gapPayloadCounters = new EnumMap<>(OrderBookRecoveryGapHandler.Status.class);
        for (OrderBookRecoveryGapHandler.Status status : OrderBookRecoveryGapHandler.Status.values()) {
            gapPayloadCounters.put(status, counter(
                "orderbook_recovery_gap_payloads_total",
                "status",
                statusValue(status)
            ));
        }
        this.snapshotDecisionCounters = new EnumMap<>(OrderBookRecoveryController.RequestStatus.class);
        for (OrderBookRecoveryController.RequestStatus status : OrderBookRecoveryController.RequestStatus.values()) {
            snapshotDecisionCounters.put(status, counter(
                "orderbook_recovery_snapshot_request_decisions_total",
                "status",
                statusValue(status)
            ));
        }
        this.snapshotRequestSuccessCounter = counter(
            "orderbook_recovery_snapshot_requests_total",
            "result",
            "success"
        );
        this.snapshotRequestInterruptedCounter = counter(
            "orderbook_recovery_snapshot_requests_total",
            "result",
            "interrupted"
        );
        this.snapshotRequestRuntimeExceptionCounter = counter(
            "orderbook_recovery_snapshot_requests_total",
            "result",
            "runtime_exception"
        );
        this.consumerPollNonEmptyCounter = counter(
            "orderbook_recovery_consumer_polls_total",
            "result",
            "non_empty"
        );
        this.consumerPollErrorCounter = counter(
            "orderbook_recovery_consumer_polls_total",
            "result",
            "error"
        );
        this.consumerFragmentsCounter = metrics.counter(
            "orderbook_recovery_consumer_fragments_total",
            SERVICE_LABELS
        );
    }

    public BackendMetrics backendMetrics() {
        return metrics;
    }

    void recordGapPayloadStatus(OrderBookRecoveryGapHandler.Status status) {
        if (status != null) {
            increment(gapPayloadCounters.get(status));
        }
    }

    void recordSnapshotRequestDecision(OrderBookRecoveryController.RequestStatus status) {
        if (status != null) {
            increment(snapshotDecisionCounters.get(status));
        }
    }

    void recordSnapshotRequestSuccess() {
        increment(snapshotRequestSuccessCounter);
    }

    void recordSnapshotRequestInterrupted() {
        increment(snapshotRequestInterruptedCounter);
    }

    void recordSnapshotRequestRuntimeException() {
        increment(snapshotRequestRuntimeExceptionCounter);
    }

    void recordConsumerPoll(int fragments) {
        if (fragments > 0) {
            increment(consumerPollNonEmptyCounter);
            add(consumerFragmentsCounter, fragments);
        }
    }

    void recordConsumerPollError() {
        increment(consumerPollErrorCounter);
    }

    void setRegisteredMarkets(long registeredMarkets) {
        setGauge("orderbook_recovery_registered_markets", registeredMarkets);
    }

    void setInflightMarkets(long inflightMarkets) {
        setGauge("orderbook_recovery_inflight_markets", inflightMarkets);
    }

    private BackendMetrics.Counter counter(String name, String labelName, String labelValue) {
        return metrics.counter(name, BackendMetrics.labels(
            "service", SERVICE,
            labelName, labelValue
        ));
    }

    private void increment(BackendMetrics.Counter counter) {
        if (counter == null) {
            return;
        }
        try {
            counter.increment();
        } catch (RuntimeException ignored) {
            // Metrics must not affect the live recovery path.
        }
    }

    private void add(BackendMetrics.Counter counter, long amount) {
        if (counter == null) {
            return;
        }
        try {
            counter.add(amount);
        } catch (RuntimeException ignored) {
            // Metrics must not affect the live recovery path.
        }
    }

    private void setGauge(String name, long value) {
        try {
            metrics.setGauge(name, SERVICE_LABELS, value);
        } catch (RuntimeException ignored) {
            // Metrics must not affect the live recovery path.
        }
    }

    private static String statusValue(Enum<?> status) {
        return status.name().toLowerCase(Locale.ROOT);
    }
}
