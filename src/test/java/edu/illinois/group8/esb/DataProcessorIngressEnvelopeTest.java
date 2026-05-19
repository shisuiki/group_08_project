package edu.illinois.group8.esb;

import edu.illinois.group8.book.OrderBookStateManager;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.MarketTrade;
import edu.illinois.group8.canonical.SerializedCanonicalEvent;
import edu.illinois.group8.ingress.KalshiIngressEnvelope;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import edu.illinois.group8.publication.CollectingEventPublisher;
import edu.illinois.group8.publication.EventPublisher;
import edu.illinois.group8.publication.EventPublisher.PublicationResult;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.CanonicalDbEvent;
import edu.illinois.group8.storage.db.CanonicalDbSink;
import edu.illinois.group8.storage.db.DbOfferResult;
import edu.illinois.group8.storage.db.DbWriterStats;
import edu.illinois.group8.storage.db.RawWsDbEventInput;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataProcessorIngressEnvelopeTest {
    private static final String TRADE_MESSAGE = """
        {"type":"trade","sid":11,"msg":{"trade_id":"abc","market_ticker":"M","yes_price_dollars":"0.360","no_price_dollars":"0.640","count_fp":"1.00","taker_side":"yes","ts_ms":1669149841000}}
        """;
    private static final String ORDERBOOK_SNAPSHOT_MESSAGE = """
        {"type":"orderbook_snapshot","sid":11,"seq":2,"msg":{"market_ticker":"M","yes_dollars_fp":[["0.4500","10.00"]],"no_dollars_fp":[["0.4000","7.00"]]}}
        """;

    @Test
    void envelopeReceiveTimestampBecomesCanonicalIngestTimestamp() {
        CollectingEventPublisher publisher = new CollectingEventPublisher();
        BackendMetrics metrics = new BackendMetrics();
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            metrics
        );

        String envelopedMessage = KalshiIngressEnvelope.wrap(
            TRADE_MESSAGE,
            123_456_789L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "live-1",
            null
        );

        processor.processMessage(envelopedMessage);

        MarketTrade trade = assertInstanceOf(MarketTrade.class, publisher.events().get(1));
        assertEquals(123_456_789L, trade.metadata().ingestTsNs());
        assertEquals(1L, metrics.get(
            "backend_ws_messages_total",
            BackendMetrics.labels("service", "backend", "source", "kalshi")
        ));
        assertEquals(envelopedMessage.length(), metrics.get(
            "backend_ws_bytes_total",
            BackendMetrics.labels("service", "backend", "source", "kalshi")
        ));
        assertTrue(metrics.prometheusText().contains(
            "backend_ws_message_age_ms_count{event_type=\"market_trade\",schema_version=\"1\",service=\"backend\",source=\"kalshi\",stream=\"canonical.trade\"} 1\n"
        ));
    }

    @Test
    void byteArrayEnvelopeReceiveTimestampBecomesCanonicalIngestTimestampAndCountsWireBytes() {
        CollectingEventPublisher publisher = new CollectingEventPublisher();
        BackendMetrics metrics = new BackendMetrics();
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            metrics
        );
        byte[] envelopedMessage = KalshiIngressEnvelope.wrapBytes(
            TRADE_MESSAGE,
            123_456_789L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "live-1",
            null
        );

        processor.processMessage(envelopedMessage);

        MarketTrade trade = assertInstanceOf(MarketTrade.class, publisher.events().get(1));
        assertEquals(123_456_789L, trade.metadata().ingestTsNs());
        Map<String, String> backendKalshiLabels = BackendMetrics.labels("service", "backend", "source", "kalshi");
        assertEquals(1L, metrics.get("backend_ws_messages_total", backendKalshiLabels));
        assertEquals(envelopedMessage.length, metrics.get("backend_ws_bytes_total", backendKalshiLabels));
        assertEquals(1L, metrics.get("backend_parser_messages_total", backendKalshiLabels));
        assertEquals(1L, metrics.get("processor.raw_events"));
    }

    @Test
    void paddedByteArrayEnvelopeSliceParsesAndCountsLogicalWireBytes() {
        CollectingEventPublisher publisher = new CollectingEventPublisher();
        BackendMetrics metrics = new BackendMetrics();
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            metrics
        );
        byte[] envelopedMessage = KalshiIngressEnvelope.wrapBytes(
            TRADE_MESSAGE,
            123_456_789L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "live-1",
            null
        );
        byte[] paddedMessage = new byte[envelopedMessage.length + 9];
        Arrays.fill(paddedMessage, (byte) '#');
        int offset = 4;
        System.arraycopy(envelopedMessage, 0, paddedMessage, offset, envelopedMessage.length);

        processor.processMessage(paddedMessage, offset, envelopedMessage.length);

        MarketTrade trade = assertInstanceOf(MarketTrade.class, publisher.events().get(1));
        assertEquals(123_456_789L, trade.metadata().ingestTsNs());
        assertEquals("M", trade.metadata().marketTicker());
        Map<String, String> backendKalshiLabels = BackendMetrics.labels("service", "backend", "source", "kalshi");
        assertEquals(1L, metrics.get("backend_ws_messages_total", backendKalshiLabels));
        assertEquals(envelopedMessage.length, metrics.get("backend_ws_bytes_total", backendKalshiLabels));
        assertEquals(1L, metrics.get("backend_parser_messages_total", backendKalshiLabels));
        assertEquals(1L, metrics.get("processor.raw_events"));
    }

    @Test
    void hotPathDistributionsAreSampledWhileCountersRemainExact() {
        CollectingEventPublisher publisher = new CollectingEventPublisher();
        BackendMetrics metrics = new BackendMetrics();
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            metrics
        );
        String envelopedMessage = KalshiIngressEnvelope.wrap(
            TRADE_MESSAGE,
            123_456_789L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "live-1",
            null
        );

        for (int i = 0; i < 65; i++) {
            processor.processMessage(envelopedMessage);
        }

        Map<String, String> backendKalshiLabels = BackendMetrics.labels("service", "backend", "source", "kalshi");
        assertEquals(65L, metrics.get("backend_ws_messages_total", backendKalshiLabels));
        assertEquals(65L * envelopedMessage.length(), metrics.get("backend_ws_bytes_total", backendKalshiLabels));
        assertEquals(65L, metrics.get("backend_parser_messages_total", backendKalshiLabels));
        assertEquals(65L, metrics.get("processor.raw_events"));
        assertEquals(65L, metrics.get("processor.canonical_events.market_trade"));
        assertEquals(130L, metrics.get("processor.publish_success"));
        assertEquals(0L, metrics.get("processor.publish_failure"));
        assertEquals(130, publisher.events().size());

        String text = metrics.prometheusText();
        assertTrue(text.contains(
            "backend_parser_latency_ns_count{service=\"backend\",source=\"kalshi\"} 2\n"
        ));
        assertTrue(text.contains(
            "backend_ws_message_age_ms_count{event_type=\"market_trade\",schema_version=\"1\",service=\"backend\",source=\"kalshi\",stream=\"canonical.trade\"} 2\n"
        ));
    }

    @Test
    void canonicalDbSinkReceivesPublishedEventAfterPublisherReturns() {
        RecordingEventPublisher publisher = new RecordingEventPublisher(true);
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        BackendMetrics metrics = new BackendMetrics();
        writer.beforeOffer = () -> {
            assertFalse(publisher.inPublish);
            assertEquals(writer.canonicalEvents.size() + 1, publisher.returnedCalls);
        };
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
        assertEquals(publisher.events().size(), publisher.returnedCalls);
        assertFalse(publisher.inPublish);
        assertSame(publisher.events().get(1), writer.canonicalEvents.get(1));
        assertNotNull(writer.canonicalEvents.get(1).metadata().publishTsNs());
        assertEquals(1L, dbOfferCount(metrics, "raw", DbOfferResult.ACCEPTED, "raw_source_event", "raw.kalshi.websocket"));
        assertEquals(1L, dbOfferCount(metrics, "canonical", DbOfferResult.ACCEPTED, "market_trade", "canonical.trade"));
    }

    @Test
    void dbOfferAcceptedCountersIncludeGeneratedPath() {
        RecordingEventPublisher publisher = new RecordingEventPublisher(true);
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        BackendMetrics metrics = new BackendMetrics();
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            metrics,
            new CanonicalDbSink(writer)
        );

        processor.processMessage(KalshiIngressEnvelope.wrap(
            ORDERBOOK_SNAPSHOT_MESSAGE,
            123_456_789L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "live-1",
            null
        ));

        assertEquals(3, writer.canonicalEvents.size());
        assertEquals(1L, dbOfferCount(metrics, "raw", DbOfferResult.ACCEPTED, "raw_source_event", "raw.kalshi.websocket"));
        assertEquals(1L, dbOfferCount(metrics, "canonical", DbOfferResult.ACCEPTED, "orderbook_snapshot", "canonical.orderbook.snapshot"));
        assertEquals(1L, dbOfferCount(metrics, "generated", DbOfferResult.ACCEPTED, "top_of_book_update", "derived.top_of_book"));
    }

    @Test
    void canonicalDbSinkReceivesSerializedPayloadAfterPublisherReturns() {
        RecordingEventPublisher publisher = new RecordingEventPublisher(true, true);
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        writer.beforeOffer = () -> {
            assertFalse(publisher.inPublish);
            assertEquals(writer.serializedCanonicalEvents.size() + 1, publisher.returnedCalls);
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

        assertEquals(publisher.events().size(), writer.serializedCanonicalEvents.size());
        assertEquals(publisher.events().size(), publisher.returnedCalls);
        assertFalse(publisher.inPublish);
        assertSame(publisher.events().get(1), writer.serializedCanonicalEvents.get(1).event());
        assertTrue(new String(
            writer.serializedCanonicalEvents.get(1).utf8Json(),
            StandardCharsets.UTF_8
        ).contains("\"stream_name\":\"canonical.trade\""));
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
        assertEquals(1L, dbOfferCount(metrics, "raw", DbOfferResult.DROPPED_FULL, "raw_source_event", "raw.kalshi.websocket"));
        assertEquals(1L, dbOfferCount(metrics, "canonical", DbOfferResult.DROPPED_FULL, "market_trade", "canonical.trade"));
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
        assertEquals(1L, dbOfferCount(metrics, "raw", DbOfferResult.DISABLED, "raw_source_event", "raw.kalshi.websocket"));
        assertEquals(1L, dbOfferCount(metrics, "canonical", DbOfferResult.DISABLED, "market_trade", "canonical.trade"));
    }

    private static long dbOfferCount(
        BackendMetrics metrics,
        String path,
        DbOfferResult result,
        String eventType,
        String stream
    ) {
        return metrics.get(
            "processor_db_offers_total",
            BackendMetrics.labels(
                "service", "backend",
                "path", path,
                "result", result.name().toLowerCase(Locale.ROOT),
                "event_type", eventType,
                "stream", stream
            )
        );
    }

    private static final class RecordingEventPublisher implements EventPublisher {
        private final List<CanonicalEvent> events = new ArrayList<>();
        private final boolean result;
        private final boolean returnSerialized;
        private final JsonCanonicalSerializer serializer = new JsonCanonicalSerializer();
        private boolean inPublish;
        private int returnedCalls;

        private RecordingEventPublisher(boolean result) {
            this(result, false);
        }

        private RecordingEventPublisher(boolean result, boolean returnSerialized) {
            this.result = result;
            this.returnSerialized = returnSerialized;
        }

        @Override
        public boolean publish(CanonicalEvent event) {
            inPublish = true;
            events.add(event);
            inPublish = false;
            returnedCalls++;
            return result;
        }

        @Override
        public PublicationResult publishSerialized(CanonicalEvent event) {
            if (!returnSerialized) {
                return EventPublisher.super.publishSerialized(event);
            }
            boolean success = publish(event);
            return new PublicationResult(event, SerializedCanonicalEvent.from(event, serializer), success);
        }

        private List<CanonicalEvent> events() {
            return Collections.unmodifiableList(events);
        }
    }

    private static final class RecordingAsyncDbWriter implements AsyncDbWriter {
        private final List<CanonicalEvent> canonicalEvents = new ArrayList<>();
        private final List<SerializedCanonicalEvent> serializedCanonicalEvents = new ArrayList<>();
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
        public DbOfferResult offerSerializedCanonicalEvent(SerializedCanonicalEvent event) {
            beforeOffer.run();
            serializedCanonicalEvents.add(event);
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
