package edu.illinois.group8.esb;

import edu.illinois.group8.book.OrderBookStateManager;
import edu.illinois.group8.book.SourceSequenceMonitor;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.CanonicalEvents;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.ingress.KalshiIngressEnvelope;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.CanonicalParseResult;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import edu.illinois.group8.publication.AeronEventPublisher;
import edu.illinois.group8.publication.EventPublisher;
import edu.illinois.group8.storage.db.CanonicalDbSink;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class DataProcessor {
    private final KalshiCanonicalParser parser;
    private final OrderBookStateManager orderBookStateManager;
    private final SourceSequenceMonitor sourceSequenceMonitor;
    private final boolean sourceSequenceMonitorEnabled;
    private final boolean orderBookDerivedEnabled;
    private final EventPublisher publisher;
    private final BackendMetrics metrics;
    private final CanonicalDbSink canonicalDbSink;
    private final BackendMetrics.Counter backendWsMessages;
    private final BackendMetrics.Counter backendWsBytes;
    private final BackendMetrics.Counter backendParserMessages;
    private final BackendMetrics.DistributionHandle backendParserLatency;
    private final BackendMetrics.Counter rawEvents;
    private final BackendMetrics.Counter publishSuccess;
    private final BackendMetrics.Counter publishFailure;
    private final ConcurrentHashMap<String, BackendMetrics.Counter> canonicalEventCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BackendMetrics.Counter> generatedEventCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventMetricKey, EventMetricHandles> eventMetricHandles = new ConcurrentHashMap<>();

    public DataProcessor(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this(communicationOrchestrator, CanonicalDbSink.disabled());
    }

    public DataProcessor(
        ESBClusterCommunicationOrchestrator communicationOrchestrator,
        CanonicalDbSink canonicalDbSink
    ) {
        this(communicationOrchestrator, new BackendMetrics(), canonicalDbSink);
    }

    private DataProcessor(ESBClusterCommunicationOrchestrator communicationOrchestrator, BackendMetrics metrics) {
        this(communicationOrchestrator, metrics, BackendConfig.fromEnvironment(), CanonicalDbSink.disabled());
    }

    public DataProcessor(
        ESBClusterCommunicationOrchestrator communicationOrchestrator,
        BackendMetrics metrics,
        CanonicalDbSink canonicalDbSink
    ) {
        this(communicationOrchestrator, metrics, BackendConfig.fromEnvironment(), canonicalDbSink);
    }

    private DataProcessor(
        ESBClusterCommunicationOrchestrator communicationOrchestrator,
        BackendMetrics metrics,
        BackendConfig config,
        CanonicalDbSink canonicalDbSink
    ) {
        this(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            new SourceSequenceMonitor(),
            config.sourceSequenceMonitorEnabled(),
            config.orderBookDerivedEnabled(),
            new AeronEventPublisher(communicationOrchestrator, new JsonCanonicalSerializer(), metrics),
            metrics,
            canonicalDbSink
        );
    }

    public DataProcessor(
        KalshiCanonicalParser parser,
        OrderBookStateManager orderBookStateManager,
        EventPublisher publisher,
        BackendMetrics metrics
    ) {
        this(parser, orderBookStateManager, publisher, metrics, CanonicalDbSink.disabled());
    }

    public DataProcessor(
        KalshiCanonicalParser parser,
        OrderBookStateManager orderBookStateManager,
        EventPublisher publisher,
        BackendMetrics metrics,
        CanonicalDbSink canonicalDbSink
    ) {
        this(parser, orderBookStateManager, new SourceSequenceMonitor(), false, true, publisher, metrics, canonicalDbSink);
    }

    public DataProcessor(
        KalshiCanonicalParser parser,
        OrderBookStateManager orderBookStateManager,
        SourceSequenceMonitor sourceSequenceMonitor,
        boolean sourceSequenceMonitorEnabled,
        boolean orderBookDerivedEnabled,
        EventPublisher publisher,
        BackendMetrics metrics
    ) {
        this(
            parser,
            orderBookStateManager,
            sourceSequenceMonitor,
            sourceSequenceMonitorEnabled,
            orderBookDerivedEnabled,
            publisher,
            metrics,
            CanonicalDbSink.disabled()
        );
    }

    public DataProcessor(
        KalshiCanonicalParser parser,
        OrderBookStateManager orderBookStateManager,
        SourceSequenceMonitor sourceSequenceMonitor,
        boolean sourceSequenceMonitorEnabled,
        boolean orderBookDerivedEnabled,
        EventPublisher publisher,
        BackendMetrics metrics,
        CanonicalDbSink canonicalDbSink
    ) {
        this.parser = parser;
        this.orderBookStateManager = orderBookStateManager;
        this.sourceSequenceMonitor = sourceSequenceMonitor;
        this.sourceSequenceMonitorEnabled = sourceSequenceMonitorEnabled;
        this.orderBookDerivedEnabled = orderBookDerivedEnabled;
        this.publisher = publisher;
        this.metrics = metrics;
        this.canonicalDbSink = Objects.requireNonNull(canonicalDbSink, "canonicalDbSink");
        Map<String, String> backendKalshiLabels = Map.of("service", "backend", "source", "kalshi");
        this.backendWsMessages = metrics.counter("backend_ws_messages_total", backendKalshiLabels);
        this.backendWsBytes = metrics.counter("backend_ws_bytes_total", backendKalshiLabels);
        this.backendParserMessages = metrics.counter("backend_parser_messages_total", backendKalshiLabels);
        this.backendParserLatency = metrics.distribution("backend_parser_latency_ns", backendKalshiLabels);
        this.rawEvents = metrics.counter("processor.raw_events");
        this.publishSuccess = metrics.counter("processor.publish_success");
        this.publishFailure = metrics.counter("processor.publish_failure");
    }

    public void processMessage(String message) {
        long parseStartTsNs = System.nanoTime();
        backendWsMessages.increment();
        backendWsBytes.add(message.length());
        KalshiIngressEnvelope ingress = KalshiIngressEnvelope.parse(message, parseStartTsNs);
        CanonicalParseResult parseResult = parser.parseWebSocketMessage(
            ingress.rawPayload(),
            ingress.receiveTsNs(),
            ingress.replayId()
        );
        backendParserMessages.increment();
        backendParserLatency.observe(Math.max(0L, System.nanoTime() - parseStartTsNs));
        publish(parseResult.rawSourceEvent());
        rawEvents.increment();

        if (sourceSequenceMonitorEnabled) {
            CanonicalEvent firstEvent = parseResult.canonicalEvents().isEmpty() ? null : parseResult.canonicalEvents().get(0);
            for (CanonicalEvent generated : sourceSequenceMonitor.apply(firstEvent)) {
                handleGeneratedEvent(generated);
            }
        }

        for (CanonicalEvent event : parseResult.canonicalEvents()) {
            observeEventMetrics(event);
            handleCanonicalEvent(event);
        }
    }

    public BackendMetrics metrics() {
        return metrics;
    }

    private void handleCanonicalEvent(CanonicalEvent event) {
        publish(event);
        canonicalEventCounter(event.eventType()).increment();

        if (orderBookDerivedEnabled) {
            for (CanonicalEvent generated : orderBookStateManager.apply(event).generatedEvents()) {
                handleGeneratedEvent(generated);
            }
        }
    }

    private void handleGeneratedEvent(CanonicalEvent generated) {
        publish(generated);
        generatedEventCounter(generated.eventType()).increment();
    }

    private void publish(CanonicalEvent event) {
        CanonicalEvent publishedEvent = CanonicalEvents.withPublishTsNs(event, System.nanoTime());
        boolean success = publisher.publish(publishedEvent);
        if (success) {
            publishSuccess.increment();
        } else {
            publishFailure.increment();
        }
        canonicalDbSink.offer(publishedEvent);
    }

    private void observeEventMetrics(CanonicalEvent event) {
        EventMetricHandles handles = eventMetricHandles.computeIfAbsent(
            EventMetricKey.from(event),
            this::eventMetricHandles
        );
        Long eventTsMs = event.metadata().eventTsMs();
        if (eventTsMs != null && eventTsMs > 0L) {
            handles.messageAge.observe(Math.max(0L, System.currentTimeMillis() - eventTsMs));
        }
        switch (event.eventType()) {
            case "parser_error" -> {
                handles.parserErrors.increment();
                if (event.eventId().contains("unsupported_message_type")) {
                    handles.unknownMessageType.increment();
                }
            }
            case "orderbook_snapshot" -> handles.orderbookSnapshot.increment();
            case "orderbook_delta" -> handles.orderbookDelta.increment();
            case "sequence_gap" -> handles.sequenceGap.increment();
            case "top_of_book_update" -> {
                if (event instanceof edu.illinois.group8.canonical.TopOfBookUpdate topOfBook && topOfBook.crossed()) {
                    handles.orderbookCrossed.increment();
                }
            }
            default -> {
            }
        }
    }

    private BackendMetrics.Counter canonicalEventCounter(String eventType) {
        return canonicalEventCounters.computeIfAbsent(
            String.valueOf(eventType),
            type -> metrics.counter("processor.canonical_events." + type)
        );
    }

    private BackendMetrics.Counter generatedEventCounter(String eventType) {
        return generatedEventCounters.computeIfAbsent(
            String.valueOf(eventType),
            type -> metrics.counter("processor.generated_events." + type)
        );
    }

    private EventMetricHandles eventMetricHandles(EventMetricKey key) {
        Map<String, String> labels = BackendMetrics.labels(
            "service", "backend",
            "stream", key.streamName(),
            "event_type", key.eventType(),
            "schema_version", Integer.toString(key.schemaVersion()),
            "source", key.source()
        );
        return new EventMetricHandles(
            metrics.distribution("backend_ws_message_age_ms", labels),
            metrics.counter("backend_parser_errors_total", labels),
            metrics.counter("backend_unknown_message_type_total", labels),
            metrics.counter("backend_orderbook_snapshot_total", labels),
            metrics.counter("backend_orderbook_delta_total", labels),
            metrics.counter("backend_orderbook_sequence_gap_total", labels),
            metrics.counter("backend_orderbook_crossed_total", labels)
        );
    }

    private static String normalizeLabelValue(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private record EventMetricKey(String streamName, String eventType, int schemaVersion, String source) {
        private static EventMetricKey from(CanonicalEvent event) {
            return new EventMetricKey(
                normalizeLabelValue(event.streamName()),
                normalizeLabelValue(event.eventType()),
                event.schemaVersion(),
                normalizeLabelValue(event.metadata().source())
            );
        }
    }

    private record EventMetricHandles(
        BackendMetrics.DistributionHandle messageAge,
        BackendMetrics.Counter parserErrors,
        BackendMetrics.Counter unknownMessageType,
        BackendMetrics.Counter orderbookSnapshot,
        BackendMetrics.Counter orderbookDelta,
        BackendMetrics.Counter sequenceGap,
        BackendMetrics.Counter orderbookCrossed
    ) {
    }
}
