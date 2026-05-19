package edu.illinois.group8.publication;

import edu.illinois.group8.canonical.EventMetadata;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.MarketTrade;
import edu.illinois.group8.canonical.SerializedCanonicalEvent;
import edu.illinois.group8.metrics.BackendMetrics;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AeronEventPublisherMetricsTest {
    @Test
    void internalPayloadBytesWrapCanonicalJsonWithRouteEnvelope() {
        MarketTrade event = new MarketTrade(
            "trade-1",
            new EventMetadata("kalshi", "ticker", 11L, 4L, "M", null, 123L, 456L, null, "raw-1", null),
            "t1",
            1_000L,
            999_000L,
            2L,
            "yes"
        );
        SerializedCanonicalEvent serializedEvent =
            SerializedCanonicalEvent.from(event, new JsonCanonicalSerializer());

        byte[] internalPayload = AeronEventPublisher.internalPayloadBytes(serializedEvent);
        CanonicalRouteEnvelope.ParseResult route =
            CanonicalRouteEnvelope.parse(internalPayload, 0, internalPayload.length);

        assertTrue(route.headerPresent());
        assertFalse(route.malformed());
        assertEquals("canonical.trade", route.streamName());
        assertArrayEquals(
            serializedEvent.utf8Json(),
            Arrays.copyOfRange(internalPayload, route.payloadOffset(), route.payloadOffset() + route.payloadLength())
        );
    }

    @Test
    void publicationMetricHandlesPreserveExistingMetricKeys() {
        BackendMetrics metrics = new BackendMetrics();
        AeronEventPublisher.PublicationMetricHandles handles =
            AeronEventPublisher.publicationMetricHandles(metrics, "canonical.trade");

        handles.offerTotal().increment();
        handles.latency().observe(123L);
        handles.offerFailed().increment();
        handles.backpressure().observe(45L);
        handles.legacyOfferFailed().increment();
        handles.legacyOfferSuccess().increment();
        handles.legacyBytes().add(99L);

        var labels = BackendMetrics.labels("service", "backend", "stream", "canonical.trade");
        assertEquals(1L, metrics.get("backend_publication_offer_total", labels));
        assertEquals(1L, metrics.get("backend_publication_offer_failed_total", labels));
        assertEquals(1L, metrics.get("publication.offer_failed.canonical.trade"));
        assertEquals(1L, metrics.get("publication.offer_success.canonical.trade"));
        assertEquals(99L, metrics.get("publication.bytes.canonical.trade"));

        String text = metrics.prometheusText();
        assertTrue(text.contains(
            "backend_publication_latency_ns_count{service=\"backend\",stream=\"canonical.trade\"} 1\n"
        ));
        assertTrue(text.contains(
            "backend_publication_latency_ns_sum{service=\"backend\",stream=\"canonical.trade\"} 123\n"
        ));
        assertTrue(text.contains(
            "backend_publication_backpressure_ns_count{service=\"backend\",stream=\"canonical.trade\"} 1\n"
        ));
        assertTrue(text.contains(
            "backend_publication_backpressure_ns_sum{service=\"backend\",stream=\"canonical.trade\"} 45\n"
        ));
    }

    @Test
    void publicationSamplerSamplesFirstAttemptAndEverySixtyFourthAfter() {
        assertTrue(AeronEventPublisher.shouldSampleHotPathDistribution(0L));
        for (long cursor = 1L; cursor < 64L; cursor++) {
            assertFalse(AeronEventPublisher.shouldSampleHotPathDistribution(cursor));
        }
        assertTrue(AeronEventPublisher.shouldSampleHotPathDistribution(64L));
        assertFalse(AeronEventPublisher.shouldSampleHotPathDistribution(65L));
    }

    @Test
    void sampledPublicationMetricsKeepSuccessCountersExact() {
        BackendMetrics metrics = new BackendMetrics();
        AeronEventPublisher.PublicationMetricHandles handles =
            AeronEventPublisher.publicationMetricHandles(metrics, "canonical.trade");

        for (int i = 0; i < 65; i++) {
            handles.offerTotal().increment();
            assertTrue(AeronEventPublisher.recordPublicationOutcome(
                handles,
                1L,
                99,
                1_000L + i,
                AeronEventPublisher.shouldSampleHotPathDistribution(i)
            ));
        }

        var labels = BackendMetrics.labels("service", "backend", "stream", "canonical.trade");
        assertEquals(65L, metrics.get("backend_publication_offer_total", labels));
        assertEquals(0L, metrics.get("backend_publication_offer_failed_total", labels));
        assertEquals(0L, metrics.get("publication.offer_failed.canonical.trade"));
        assertEquals(65L, metrics.get("publication.offer_success.canonical.trade"));
        assertEquals(6_435L, metrics.get("publication.bytes.canonical.trade"));

        String text = metrics.prometheusText();
        assertTrue(text.contains(
            "backend_publication_latency_ns_count{service=\"backend\",stream=\"canonical.trade\"} 2\n"
        ));
    }

    @Test
    void sampledPublicationMetricsKeepFailureCountersExact() {
        BackendMetrics metrics = new BackendMetrics();
        AeronEventPublisher.PublicationMetricHandles handles =
            AeronEventPublisher.publicationMetricHandles(metrics, "canonical.trade");

        for (int i = 0; i < 65; i++) {
            handles.offerTotal().increment();
            assertFalse(AeronEventPublisher.recordPublicationOutcome(
                handles,
                -1L,
                99,
                2_000L + i,
                AeronEventPublisher.shouldSampleHotPathDistribution(i)
            ));
        }

        var labels = BackendMetrics.labels("service", "backend", "stream", "canonical.trade");
        assertEquals(65L, metrics.get("backend_publication_offer_total", labels));
        assertEquals(65L, metrics.get("backend_publication_offer_failed_total", labels));
        assertEquals(65L, metrics.get("publication.offer_failed.canonical.trade"));
        assertEquals(0L, metrics.get("publication.offer_success.canonical.trade"));
        assertEquals(0L, metrics.get("publication.bytes.canonical.trade"));

        String text = metrics.prometheusText();
        assertTrue(text.contains(
            "backend_publication_latency_ns_count{service=\"backend\",stream=\"canonical.trade\"} 2\n"
        ));
        assertTrue(text.contains(
            "backend_publication_backpressure_ns_count{service=\"backend\",stream=\"canonical.trade\"} 2\n"
        ));
    }
}
