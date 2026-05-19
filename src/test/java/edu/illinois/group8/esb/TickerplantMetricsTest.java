package edu.illinois.group8.esb;

import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.publication.CanonicalRouteEnvelope;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickerplantMetricsTest {
    @Test
    void routeMetricHandlesPreserveExistingMetricKeys() {
        BackendMetrics metrics = new BackendMetrics();
        Tickerplant.RouteMetricHandles handles =
            Tickerplant.routeMetricHandles(metrics, "canonical.orderbook_delta");

        handles.offerTotal().increment();
        handles.latency().observe(222L);
        handles.offerFailed().increment();
        handles.backpressure().observe(33L);
        handles.routeOfferFailed().increment();
        handles.routeSuccess().increment();

        var labels = BackendMetrics.labels("service", "tickerplant", "stream", "canonical.orderbook_delta");
        assertEquals(1L, metrics.get("backend_publication_offer_total", labels));
        assertEquals(1L, metrics.get("backend_publication_offer_failed_total", labels));
        assertEquals(1L, metrics.get("tickerplant.route_failed.offer.canonical.orderbook_delta"));
        assertEquals(1L, metrics.get("tickerplant.route_success.canonical.orderbook_delta"));

        String text = metrics.prometheusText();
        assertTrue(text.contains(
            "backend_publication_latency_ns_count{service=\"tickerplant\",stream=\"canonical.orderbook_delta\"} 1\n"
        ));
        assertTrue(text.contains(
            "backend_publication_latency_ns_sum{service=\"tickerplant\",stream=\"canonical.orderbook_delta\"} 222\n"
        ));
        assertTrue(text.contains(
            "backend_publication_backpressure_ns_count{service=\"tickerplant\",stream=\"canonical.orderbook_delta\"} 1\n"
        ));
        assertTrue(text.contains(
            "backend_publication_backpressure_ns_sum{service=\"tickerplant\",stream=\"canonical.orderbook_delta\"} 33\n"
        ));
    }

    @Test
    void routeSamplerSamplesFirstAttemptAndEverySixtyFourthAfter() {
        assertTrue(Tickerplant.shouldSampleHotPathDistribution(0L));
        for (long cursor = 1L; cursor < 64L; cursor++) {
            assertFalse(Tickerplant.shouldSampleHotPathDistribution(cursor));
        }
        assertTrue(Tickerplant.shouldSampleHotPathDistribution(64L));
        assertFalse(Tickerplant.shouldSampleHotPathDistribution(65L));
    }

    @Test
    void sampledRouteMetricsKeepSuccessCountersExact() {
        BackendMetrics metrics = new BackendMetrics();
        Tickerplant.RouteMetricHandles handles =
            Tickerplant.routeMetricHandles(metrics, "canonical.orderbook_delta");

        for (int i = 0; i < 65; i++) {
            handles.offerTotal().increment();
            assertTrue(Tickerplant.recordRouteOutcome(
                handles,
                1L,
                1_000L + i,
                Tickerplant.shouldSampleHotPathDistribution(i)
            ));
        }

        var labels = BackendMetrics.labels("service", "tickerplant", "stream", "canonical.orderbook_delta");
        assertEquals(65L, metrics.get("backend_publication_offer_total", labels));
        assertEquals(0L, metrics.get("backend_publication_offer_failed_total", labels));
        assertEquals(0L, metrics.get("tickerplant.route_failed.offer.canonical.orderbook_delta"));
        assertEquals(65L, metrics.get("tickerplant.route_success.canonical.orderbook_delta"));

        String text = metrics.prometheusText();
        assertTrue(text.contains(
            "backend_publication_latency_ns_count{service=\"tickerplant\",stream=\"canonical.orderbook_delta\"} 2\n"
        ));
    }

    @Test
    void sampledRouteMetricsKeepFailureCountersExact() {
        BackendMetrics metrics = new BackendMetrics();
        Tickerplant.RouteMetricHandles handles =
            Tickerplant.routeMetricHandles(metrics, "canonical.orderbook_delta");

        for (int i = 0; i < 65; i++) {
            handles.offerTotal().increment();
            assertFalse(Tickerplant.recordRouteOutcome(
                handles,
                -1L,
                2_000L + i,
                Tickerplant.shouldSampleHotPathDistribution(i)
            ));
        }

        var labels = BackendMetrics.labels("service", "tickerplant", "stream", "canonical.orderbook_delta");
        assertEquals(65L, metrics.get("backend_publication_offer_total", labels));
        assertEquals(65L, metrics.get("backend_publication_offer_failed_total", labels));
        assertEquals(65L, metrics.get("tickerplant.route_failed.offer.canonical.orderbook_delta"));
        assertEquals(0L, metrics.get("tickerplant.route_success.canonical.orderbook_delta"));

        String text = metrics.prometheusText();
        assertTrue(text.contains(
            "backend_publication_latency_ns_count{service=\"tickerplant\",stream=\"canonical.orderbook_delta\"} 2\n"
        ));
        assertTrue(text.contains(
            "backend_publication_backpressure_ns_count{service=\"tickerplant\",stream=\"canonical.orderbook_delta\"} 2\n"
        ));
    }

    @Test
    void resolveRouteUsesHeaderStreamAndCanonicalPayloadSlice() throws Exception {
        byte[] payload = bytes("{\"event_id\":\"e1\",\"stream_name\":\"derived.top_of_book\"}");
        byte[] envelope = CanonicalRouteEnvelope.wrap("canonical.trade", payload);

        Tickerplant.RoutePayload route = Tickerplant.resolveRoute(envelope, 0, envelope.length);

        assertTrue(route.headerRouted());
        assertEquals("canonical.trade", route.streamName());
        assertEquals(payload.length, route.payloadLength());
        assertEquals(
            new String(payload, StandardCharsets.UTF_8),
            new String(envelope, route.payloadOffset(), route.payloadLength(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void resolveRouteSupportsHeaderWithNonZeroOffsetSlice() throws Exception {
        byte[] payload = bytes("{\"event_id\":\"e1\"}");
        byte[] envelope = CanonicalRouteEnvelope.wrap("system.sequence_gaps", payload);
        byte[] data = new byte[envelope.length + 4];
        data[0] = 'x';
        data[1] = 'x';
        System.arraycopy(envelope, 0, data, 2, envelope.length);
        data[data.length - 2] = 'y';
        data[data.length - 1] = 'y';

        Tickerplant.RoutePayload route = Tickerplant.resolveRoute(data, 2, envelope.length);

        assertTrue(route.headerRouted());
        assertEquals("system.sequence_gaps", route.streamName());
        assertEquals(payload.length, route.payloadLength());
        assertEquals(
            new String(payload, StandardCharsets.UTF_8),
            new String(data, route.payloadOffset(), route.payloadLength(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void resolveRouteFallsBackToLegacyJsonStreamNameWhenNoHeaderMagic() throws Exception {
        byte[] payload = bytes("{\"event_id\":\"e1\",\"stream_name\":\"canonical.trade\"}");

        Tickerplant.RoutePayload route = Tickerplant.resolveRoute(payload, 0, payload.length);

        assertFalse(route.headerRouted());
        assertEquals("canonical.trade", route.streamName());
        assertEquals(0, route.payloadOffset());
        assertEquals(payload.length, route.payloadLength());
    }

    @Test
    void resolveRouteRejectsMalformedRouteHeaderWithoutJsonFallback() {
        byte[] payload = bytes("{\"event_id\":\"e1\",\"stream_name\":\"canonical.trade\"}");
        byte[] envelope = CanonicalRouteEnvelope.wrap("canonical.trade", payload);

        assertThrows(
            IOException.class,
            () -> Tickerplant.resolveRoute(envelope, 0, envelope.length - 1)
        );
    }

    @Test
    void extractsTopLevelStreamName() throws Exception {
        String streamName = Tickerplant.extractTopLevelStreamName("""
            {"event_id":"e1","stream_name":"derived.top_of_book","metadata":{"stream_name":"canonical.trade"}}
            """);

        assertEquals("derived.top_of_book", streamName);
    }

    @Test
    void extractsTopLevelStreamNameFromBytes() throws Exception {
        byte[] data = bytes("""
            {"event_id":"e1","stream_name":"derived.top_of_book","metadata":{"stream_name":"canonical.trade"}}
            """);

        assertEquals("derived.top_of_book", Tickerplant.extractTopLevelStreamName(data, 0, data.length));
    }

    @Test
    void nestedStreamNameDoesNotRouteAsTopLevel() throws Exception {
        String streamName = Tickerplant.extractTopLevelStreamName("""
            {"event_id":"e1","metadata":{"stream_name":"canonical.trade"}}
            """);

        assertNull(streamName);
    }

    @Test
    void byteExtractionIgnoresNestedOnlyStreamName() throws Exception {
        byte[] data = bytes("""
            {"event_id":"e1","metadata":{"stream_name":"canonical.trade"}}
            """);

        assertNull(Tickerplant.extractTopLevelStreamName(data, 0, data.length));
    }

    @Test
    void byteExtractionHonorsNonZeroOffsetSlice() throws Exception {
        String payload = "{\"event_id\":\"e1\",\"stream_name\":\"derived.top_of_book\"}";
        byte[] payloadBytes = bytes(payload);
        byte[] data = bytes("xx" + payload + "yy");

        assertEquals(
            "derived.top_of_book",
            Tickerplant.extractTopLevelStreamName(data, 2, payloadBytes.length)
        );
    }

    @Test
    void missingAndBlankStreamNameAreReturnedForRouteFailureHandling() throws Exception {
        assertNull(Tickerplant.extractTopLevelStreamName("{\"event_id\":\"e1\"}"));

        String blank = Tickerplant.extractTopLevelStreamName("""
            {"event_id":"e1","stream_name":"   "}
            """);
        assertTrue(blank.isBlank());
    }

    @Test
    void malformedJsonThrowsForParseFailureHandling() {
        assertThrows(
            IOException.class,
            () -> Tickerplant.extractTopLevelStreamName("{\"stream_name\":\"canonical.trade\"")
        );
    }

    @Test
    void byteExtractionThrowsForMalformedAndTrailingJson() {
        assertThrows(
            IOException.class,
            () -> {
                byte[] data = bytes("{\"stream_name\":\"canonical.trade\"");
                Tickerplant.extractTopLevelStreamName(data, 0, data.length);
            }
        );
        assertThrows(
            IOException.class,
            () -> {
                byte[] data = bytes("{\"stream_name\":\"canonical.trade\"}{}");
                Tickerplant.extractTopLevelStreamName(data, 0, data.length);
            }
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
