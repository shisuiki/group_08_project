package edu.illinois.group8.publication;

import edu.illinois.group8.metrics.BackendMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AeronEventPublisherMetricsTest {
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
}
