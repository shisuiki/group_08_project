package edu.illinois.group8.esb;

import edu.illinois.group8.book.OrderBookStateManager;
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

public class DataProcessor {
    private final KalshiCanonicalParser parser;
    private final OrderBookStateManager orderBookStateManager;
    private final EventPublisher publisher;
    private final EventJournal journal;
    private final BackendMetrics metrics;

    public DataProcessor(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this(communicationOrchestrator, new BackendMetrics());
    }

    private DataProcessor(ESBClusterCommunicationOrchestrator communicationOrchestrator, BackendMetrics metrics) {
        this(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            new AeronEventPublisher(communicationOrchestrator, new JsonCanonicalSerializer(), metrics),
            new FileEventJournal(BackendConfig.fromEnvironment().journalRoot()),
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
        this.parser = parser;
        this.orderBookStateManager = orderBookStateManager;
        this.publisher = publisher;
        this.journal = journal;
        this.metrics = metrics;
    }

    public void processMessage(String message) {
        CanonicalParseResult parseResult = parser.parseWebSocketMessage(message, System.nanoTime());
        journal.appendRaw(parseResult.rawSourceEvent());
        publish(parseResult.rawSourceEvent());
        metrics.increment("processor.raw_events");

        for (CanonicalEvent event : parseResult.canonicalEvents()) {
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

        for (CanonicalEvent generated : orderBookStateManager.apply(event).generatedEvents()) {
            journal.appendCanonical(generated);
            publish(generated);
            metrics.increment("processor.generated_events." + generated.eventType());
        }
    }

    private void publish(CanonicalEvent event) {
        CanonicalEvent publishedEvent = CanonicalEvents.withPublishTsNs(event, System.nanoTime());
        boolean success = publisher.publish(publishedEvent);
        metrics.increment(success ? "processor.publish_success" : "processor.publish_failure");
    }
}
