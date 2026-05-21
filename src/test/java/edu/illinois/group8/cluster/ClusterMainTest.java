package edu.illinois.group8.cluster;

import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.metrics.BackendMetrics;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClusterMainTest {
    @Test
    void metricsServerFactoryUsesConfiguredEndpointAndSharedMetrics() throws Exception {
        BackendConfig config = BackendConfig.from(Map.of(
            "NODE_ID", "0",
            "BACKEND_METRICS_HOST", "127.0.0.1",
            "BACKEND_METRICS_PORT", "19095"
        ));
        BackendMetrics sharedMetrics = new BackendMetrics();
        AtomicReference<String> capturedHost = new AtomicReference<>();
        AtomicInteger capturedPort = new AtomicInteger();
        AtomicReference<BackendMetrics> capturedMetrics = new AtomicReference<>();
        AtomicInteger closeCalls = new AtomicInteger();

        AutoCloseable server = ClusterMain.startMetricsServer(config, sharedMetrics, (host, port, metrics) -> {
            capturedHost.set(host);
            capturedPort.set(port);
            capturedMetrics.set(metrics);
            return closeCalls::incrementAndGet;
        });

        assertEquals("127.0.0.1", capturedHost.get());
        assertEquals(19095, capturedPort.get());
        assertSame(sharedMetrics, capturedMetrics.get());

        server.close();

        assertEquals(1, closeCalls.get());
    }

    @Test
    void metricsServerFactoryNoopsWhenDisabled() {
        BackendConfig config = BackendConfig.from(Map.of(
            "NODE_ID", "0",
            "BACKEND_METRICS_PORT", "0"
        ));
        AtomicInteger factoryCalls = new AtomicInteger();

        AutoCloseable server = ClusterMain.startMetricsServer(config, new BackendMetrics(), (host, port, metrics) -> {
            factoryCalls.incrementAndGet();
            return () -> {
            };
        });

        assertNull(server);
        assertEquals(0, factoryCalls.get());
    }
}
