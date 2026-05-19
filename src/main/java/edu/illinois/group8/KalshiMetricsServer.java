package edu.illinois.group8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.illinois.group8.metrics.BackendMetrics;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class KalshiMetricsServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final BackendMetrics metrics;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private KalshiMetricsServer(HttpServer server, ExecutorService executor, BackendMetrics metrics) {
        this.server = Objects.requireNonNull(server, "server");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public static KalshiMetricsServer start(String host, int port, BackendMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0) {
            throw new IllegalArgumentException("port must be zero or positive");
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
            ExecutorService executor = Executors.newFixedThreadPool(2, daemonThreadFactory());
            KalshiMetricsServer metricsServer = new KalshiMetricsServer(server, executor, metrics);
            server.createContext("/metrics", metricsServer::handleMetrics);
            server.createContext("/health", metricsServer::handleHealth);
            server.setExecutor(executor);
            server.start();
            return metricsServer;
        } catch (IOException exc) {
            throw new IllegalStateException("Failed to start Kalshi metrics server on " + host + ":" + port, exc);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, "text/plain; charset=utf-8", "method not allowed\n");
            return;
        }
        write(exchange, 200, "text/plain; charset=utf-8", metrics.prometheusText());
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, "text/plain; charset=utf-8", "method not allowed\n");
            return;
        }
        write(exchange, 200, "text/plain; charset=utf-8", "status ok\n");
    }

    private static void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "kalshi-metrics-server-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
