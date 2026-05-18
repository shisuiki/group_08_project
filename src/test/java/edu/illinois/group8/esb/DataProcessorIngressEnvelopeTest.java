package edu.illinois.group8.esb;

import edu.illinois.group8.book.OrderBookStateManager;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.MarketTrade;
import edu.illinois.group8.ingress.KalshiIngressEnvelope;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import edu.illinois.group8.publication.CollectingEventPublisher;
import edu.illinois.group8.publication.EventPublisher;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.CanonicalDbEvent;
import edu.illinois.group8.storage.db.CanonicalDbSink;
import edu.illinois.group8.storage.db.DbOfferResult;
import edu.illinois.group8.storage.db.DbWriterStats;
import edu.illinois.group8.storage.db.RawWsDbEventInput;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DataProcessorIngressEnvelopeTest {
    private static final String TRADE_MESSAGE = """
        {"type":"trade","sid":11,"msg":{"trade_id":"abc","market_ticker":"M","yes_price_dollars":"0.360","no_price_dollars":"0.640","count_fp":"1.00","taker_side":"yes","ts_ms":1669149841000}}
        """;

    @Test
    void envelopeReceiveTimestampBecomesCanonicalIngestTimestamp() {
        CollectingEventPublisher publisher = new CollectingEventPublisher();
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            new BackendMetrics()
        );

        processor.processMessage(KalshiIngressEnvelope.wrap(
            TRADE_MESSAGE,
            123_456_789L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "live-1",
            null
        ));

        MarketTrade trade = assertInstanceOf(MarketTrade.class, publisher.events().get(1));
        assertEquals(123_456_789L, trade.metadata().ingestTsNs());
    }

    @Test
    void canonicalDbSinkReceivesPublishedEventAfterPublisherReturns() {
        RecordingEventPublisher publisher = new RecordingEventPublisher(true);
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        writer.beforeOffer = () -> {
            assertFalse(publisher.inPublish);
            assertEquals(writer.canonicalEvents.size() + 1, publisher.returnedCalls);
        };
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            new BackendMetrics(),
            new CanonicalDbSink(writer)
        );

        processor.processMessage(KalshiIngressEnvelope.wrap(
            TRADE_MESSAGE,
            123_456_789L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "live-1",
            null
        ));

        assertEquals(publisher.events().size(), writer.canonicalEvents.size());
        assertEquals(publisher.events().size(), publisher.returnedCalls);
        assertFalse(publisher.inPublish);
        assertSame(publisher.events().get(1), writer.canonicalEvents.get(1));
        assertNotNull(writer.canonicalEvents.get(1).metadata().publishTsNs());
    }

    @Test
    void canonicalDbOfferDropDoesNotAffectPublishSuccessMetric() {
        RecordingEventPublisher publisher = new RecordingEventPublisher(true);
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        writer.canonicalResult = DbOfferResult.DROPPED_FULL;
        BackendMetrics metrics = new BackendMetrics();
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            metrics,
            new CanonicalDbSink(writer)
        );

        processor.processMessage(KalshiIngressEnvelope.wrap(
            TRADE_MESSAGE,
            123_456_789L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "live-1",
            null
        ));

        assertEquals(publisher.events().size(), writer.canonicalEvents.size());
        assertEquals(publisher.events().size(), metrics.get("processor.publish_success"));
        assertEquals(0L, metrics.get("processor.publish_failure"));
    }

    @Test
    void canonicalDbDisabledResultDoesNotAffectPublishFailureMetric() {
        RecordingEventPublisher publisher = new RecordingEventPublisher(false);
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        writer.canonicalResult = DbOfferResult.DISABLED;
        BackendMetrics metrics = new BackendMetrics();
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            metrics,
            new CanonicalDbSink(writer)
        );

        processor.processMessage(KalshiIngressEnvelope.wrap(
            TRADE_MESSAGE,
            123_456_789L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "live-1",
            null
        ));

        assertEquals(publisher.events().size(), writer.canonicalEvents.size());
        assertEquals(0L, metrics.get("processor.publish_success"));
        assertEquals(publisher.events().size(), metrics.get("processor.publish_failure"));
    }

    private static final class RecordingEventPublisher implements EventPublisher {
        private final List<CanonicalEvent> events = new ArrayList<>();
        private final boolean result;
        private boolean inPublish;
        private int returnedCalls;

        private RecordingEventPublisher(boolean result) {
            this.result = result;
        }

        @Override
        public boolean publish(CanonicalEvent event) {
            inPublish = true;
            events.add(event);
            inPublish = false;
            returnedCalls++;
            return result;
        }

        private List<CanonicalEvent> events() {
            return Collections.unmodifiableList(events);
        }
    }

    private static final class RecordingAsyncDbWriter implements AsyncDbWriter {
        private final List<CanonicalEvent> canonicalEvents = new ArrayList<>();
        private DbOfferResult canonicalResult = DbOfferResult.ACCEPTED;
        private Runnable beforeOffer = () -> {
        };

        @Override
        public DbOfferResult offerRaw(RawWsDbEventInput input) {
            throw new UnsupportedOperationException("raw writes are out of scope");
        }

        @Override
        public DbOfferResult offerCanonical(CanonicalDbEvent event) {
            throw new UnsupportedOperationException("pre-mapped canonical writes are out of scope");
        }

        @Override
        public DbOfferResult offerCanonicalEvent(CanonicalEvent event) {
            beforeOffer.run();
            canonicalEvents.add(event);
            return canonicalResult;
        }

        @Override
        public DbWriterStats stats() {
            return DbWriterStats.empty();
        }

        @Override
        public void close() {
        }
    }
}
