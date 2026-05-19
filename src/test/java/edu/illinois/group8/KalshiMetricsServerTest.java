package edu.illinois.group8;

import edu.illinois.group8.metrics.BackendMetrics;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KalshiMetricsServerTest {
    @Test
    void exposesPrometheusMetricsAndHealth() throws Exception {
        BackendMetrics metrics = new BackendMetrics();
        metrics.increment("wsclient_test_counter_total", BackendMetrics.labels("service", "wsclient"));

        KalshiMetricsServer server = KalshiMetricsServer.start("127.0.0.1", 0, metrics);
        try {
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> metricsResponse = client.send(
                request(server, "/metrics"),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> healthResponse = client.send(
                request(server, "/health"),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, metricsResponse.statusCode());
            assertTrue(metricsResponse.headers().firstValue("Content-Type").orElse("")
                .contains("text/plain"));
            assertTrue(metricsResponse.body()
                .contains("wsclient_test_counter_total{service=\"wsclient\"} 1\n"));
            assertEquals(200, healthResponse.statusCode());
            assertTrue(healthResponse.body().contains("status ok"));
        } finally {
            server.close();
        }

        assertDoesNotThrow(server::close);
    }

    private static HttpRequest request(KalshiMetricsServer server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .GET()
            .build();
    }
}
