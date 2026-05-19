package edu.illinois.group8.esb;

import edu.illinois.group8.metrics.BackendMetrics;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void extractsTopLevelStreamName() throws Exception {
        String streamName = Tickerplant.extractTopLevelStreamName("""
            {"event_id":"e1","stream_name":"derived.top_of_book","metadata":{"stream_name":"canonical.trade"}}
            """);

        assertEquals("derived.top_of_book", streamName);
    }

    @Test
    void nestedStreamNameDoesNotRouteAsTopLevel() throws Exception {
        String streamName = Tickerplant.extractTopLevelStreamName("""
            {"event_id":"e1","metadata":{"stream_name":"canonical.trade"}}
            """);

        assertNull(streamName);
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
}
