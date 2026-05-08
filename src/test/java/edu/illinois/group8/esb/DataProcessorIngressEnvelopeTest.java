package edu.illinois.group8.esb;

import edu.illinois.group8.book.OrderBookStateManager;
import edu.illinois.group8.canonical.MarketTrade;
import edu.illinois.group8.ingress.KalshiIngressEnvelope;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import edu.illinois.group8.publication.CollectingEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DataProcessorIngressEnvelopeTest {
    @Test
    void envelopeReceiveTimestampBecomesCanonicalIngestTimestamp() {
        CollectingEventPublisher publisher = new CollectingEventPublisher();
        DataProcessor processor = new DataProcessor(
            new KalshiCanonicalParser(),
            new OrderBookStateManager(),
            publisher,
            new BackendMetrics()
        );
        String rawPayload = """
            {"type":"trade","sid":11,"msg":{"trade_id":"abc","market_ticker":"M","yes_price_dollars":"0.360","no_price_dollars":"0.640","count_fp":"1.00","taker_side":"yes","ts_ms":1669149841000}}
            """;

        processor.processMessage(KalshiIngressEnvelope.wrap(
            rawPayload,
            123_456_789L,
            Instant.parse("2026-05-08T00:00:00Z"),
            "live-1",
            null
        ));

        MarketTrade trade = assertInstanceOf(MarketTrade.class, publisher.events().get(1));
        assertEquals(123_456_789L, trade.metadata().ingestTsNs());
    }
}
