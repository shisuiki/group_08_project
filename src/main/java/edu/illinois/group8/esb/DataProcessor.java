package edu.illinois.group8.esb;

import edu.illinois.group8.book.OrderBookStateManager;
import edu.illinois.group8.book.SourceSequenceMonitor;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.CanonicalEvents;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.CanonicalParseResult;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import edu.illinois.group8.persistence.EventJournal;
import edu.illinois.group8.persistence.FileEventJournal;
import edu.illinois.group8.publication.AeronEventPublisher;
import edu.illinois.group8.publication.EventPublisher;
import edu.illinois.group8.recorder.StreamRecordingWriter;
import edu.illinois.group8.time.TimestampSource;

public class DataProcessor {
    private final KalshiCanonicalParser parser;
    private final OrderBookStateManager orderBookStateManager;
    private final SourceSequenceMonitor sourceSequenceMonitor;
    private final boolean sourceSequenceMonitorEnabled;
    private final boolean orderBookDerivedEnabled;
    private final EventPublisher publisher;
    private final EventJournal journal;
    private final BackendMetrics metrics;

    public DataProcessor(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this(communicationOrchestrator, new BackendMetrics());
    }

    private DataProcessor(ESBClusterCommunicationOrchestrator communicationOrchestrator, BackendMetrics metrics) {
        this(communicationOrchestrator, metrics, BackendConfig.fromEnvironment());
    }

    private DataProcessor(
        ESBClusterCommunicationOrchestrator communicationOrchestrator,
        BackendMetrics metrics,
        BackendConfig config
    ) {
        this(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            new SourceSequenceMonitor(),
            config.sourceSequenceMonitorEnabled(),
            config.orderBookDerivedEnabled(),
            new AeronEventPublisher(communicationOrchestrator, new JsonCanonicalSerializer(), metrics),
            new FileEventJournal(
                config.journalRoot(),
                new JsonCanonicalSerializer(),
                metrics,
                config.legacyJournalEnabled(),
                config.rawRecordingRootOptional().orElse(null),
                config.canonicalRecordingRootOptional().orElse(null),
                TimestampSource.fromEnvironment(),
                StreamRecordingWriter.PartitionGranularity.from(config.recordingPartitionGranularity())
            ),
            metrics
        );
    }

    public DataProcessor(
        KalshiCanonicalParser parser,
        OrderBookStateManager orderBookStateManager,
        EventPublisher publisher,
        EventJournal journal,
        BackendMetrics metrics
    ) {
        this(parser, orderBookStateManager, new SourceSequenceMonitor(), false, true, publisher, journal, metrics);
    }

    public DataProcessor(
        KalshiCanonicalParser parser,
        OrderBookStateManager orderBookStateManager,
        SourceSequenceMonitor sourceSequenceMonitor,
        boolean sourceSequenceMonitorEnabled,
        boolean orderBookDerivedEnabled,
        EventPublisher publisher,
        EventJournal journal,
        BackendMetrics metrics
    ) {
        this.parser = parser;
        this.orderBookStateManager = orderBookStateManager;
        this.sourceSequenceMonitor = sourceSequenceMonitor;
        this.sourceSequenceMonitorEnabled = sourceSequenceMonitorEnabled;
        this.orderBookDerivedEnabled = orderBookDerivedEnabled;
        this.publisher = publisher;
        this.journal = journal;
        this.metrics = metrics;
    }

    public void processMessage(String message) {
        long parseStartTsNs = System.nanoTime();
        metrics.increment("backend_ws_messages_total", BackendMetrics.labels("service", "backend", "source", "kalshi"));
        metrics.add("backend_ws_bytes_total", BackendMetrics.labels("service", "backend", "source", "kalshi"), message.length());
        CanonicalParseResult parseResult = parser.parseWebSocketMessage(message, parseStartTsNs);
        metrics.increment("backend_parser_messages_total", BackendMetrics.labels("service", "backend", "source", "kalshi"));
        metrics.observe("backend_parser_latency_ns", BackendMetrics.labels("service", "backend", "source", "kalshi"),
            Math.max(0L, System.nanoTime() - parseStartTsNs));
        journal.appendRaw(parseResult.rawSourceEvent());
        publish(parseResult.rawSourceEvent());
        metrics.increment("processor.raw_events");

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
        journal.appendCanonical(event);
        publish(event);
        metrics.increment("processor.canonical_events." + event.eventType());

        if (orderBookDerivedEnabled) {
            for (CanonicalEvent generated : orderBookStateManager.apply(event).generatedEvents()) {
                handleGeneratedEvent(generated);
            }
        }
    }

    private void handleGeneratedEvent(CanonicalEvent generated) {
        journal.appendCanonical(generated);
        publish(generated);
        metrics.increment("processor.generated_events." + generated.eventType());
    }

    private void publish(CanonicalEvent event) {
        CanonicalEvent publishedEvent = CanonicalEvents.withPublishTsNs(event, System.nanoTime());
        boolean success = publisher.publish(publishedEvent);
        metrics.increment(success ? "processor.publish_success" : "processor.publish_failure");
    }

    private void observeEventMetrics(CanonicalEvent event) {
        var labels = BackendMetrics.labels(
            "service", "backend",
            "stream", event.streamName(),
            "event_type", event.eventType(),
            "schema_version", Integer.toString(event.schemaVersion()),
            "source", event.metadata().source()
        );
        Long eventTsMs = event.metadata().eventTsMs();
        if (eventTsMs != null && eventTsMs > 0L) {
            metrics.observe("backend_ws_message_age_ms", labels, Math.max(0L, System.currentTimeMillis() - eventTsMs));
        }
        switch (event.eventType()) {
            case "parser_error" -> {
                metrics.increment("backend_parser_errors_total", labels);
                if (event.eventId().contains("unsupported_message_type")) {
                    metrics.increment("backend_unknown_message_type_total", labels);
                }
            }
            case "orderbook_snapshot" -> metrics.increment("backend_orderbook_snapshot_total", labels);
            case "orderbook_delta" -> metrics.increment("backend_orderbook_delta_total", labels);
            case "sequence_gap" -> metrics.increment("backend_orderbook_sequence_gap_total", labels);
            case "top_of_book_update" -> {
                if (event instanceof edu.illinois.group8.canonical.TopOfBookUpdate topOfBook && topOfBook.crossed()) {
                    metrics.increment("backend_orderbook_crossed_total", labels);
                }
            }
            default -> {
            }
        }
    }
}
