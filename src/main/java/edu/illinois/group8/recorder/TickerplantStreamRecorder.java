package edu.illinois.group8.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.metrics.BackendMetrics;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class TickerplantStreamRecorder implements AutoCloseable {
    private final StreamRecorderConfig config;
    private final BackendMetrics metrics = new BackendMetrics();
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();
    private final StreamRecordingWriter writer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong totalEvents = new AtomicLong();
    private final ArrayDeque<Map<String, Object>> recentEvents = new ArrayDeque<>();
    private final long startTimeMs = System.currentTimeMillis();
    private MediaDriver mediaDriver;
    private Aeron aeron;
    private List<Subscription> subscriptions = List.of();
    private HttpServer httpServer;

    public TickerplantStreamRecorder(StreamRecorderConfig config) {
        this.config = config;
        this.writer = new StreamRecordingWriter(
            config.outputRoot(),
            config.timestampSource(),
            metrics,
            config.partitionGranularity()
        );
    }

    public static void main(String[] args) throws Exception {
        TickerplantStreamRecorder recorder = new TickerplantStreamRecorder(StreamRecorderConfig.fromEnvironment());
        recorder.start();
    }

    public void start() throws Exception {
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .termBufferSparseFile(true));
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        subscriptions = config.streams().stream()
            .map(stream -> aeron.addSubscription(config.aeronChannel(), stream.streamId()))
            .toList();

        startHttp();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));

        System.out.println("TickerplantStreamRecorder recording channel=" + config.aeronChannel()
            + " output_root=" + config.outputRoot()
            + " partition_granularity=" + config.partitionGranularity()
            + " idle_sleep_ms=" + config.idleSleepMillis()
            + " streams=" + config.streams().stream().map(StreamContract::streamName).toList());
        poll();
    }

    @Override
    public void close() {
        running.set(false);
        if (httpServer != null) {
            httpServer.stop(1);
        }
        subscriptions.forEach(Subscription::close);
        if (aeron != null) {
            aeron.close();
        }
        if (mediaDriver != null) {
            mediaDriver.close();
        }
    }

    private void poll() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int fragments = 0;
            for (int i = 0; i < subscriptions.size(); i++) {
                Subscription subscription = subscriptions.get(i);
                StreamContract stream = config.streams().get(i);
                fragments += subscription.poll((buffer, offset, length, header) -> {
                    byte[] bytes = new byte[length];
                    buffer.getBytes(offset, bytes);
                    String payload = new String(bytes, StandardCharsets.UTF_8);
                    record(stream.streamName(), payload, bytes.length);
                }, 10);
            }
            metrics.setGauge("backend_storage_queue_depth", 0L);
            if (fragments == 0) {
                idle();
            }
        }
    }

    private void idle() {
        if (config.idleSleepMillis() == 0) {
            Thread.onSpinWait();
            return;
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(config.idleSleepMillis()));
    }

    private void record(String streamName, String payload, int bytes) {
        long receiveTsNs = config.timestampSource().nowNanos();
        Map<String, String> labels = BackendMetrics.labels("service", "stream_recorder", "stream", streamName);
        metrics.increment("stream_recorder_events_total", labels);
        metrics.add("stream_recorder_bytes_total", labels, bytes);
        try {
            JsonNode recorded = writer.write(streamName, payload, receiveTsNs);
            totalEvents.incrementAndGet();
            observeEventQuality(streamName, recorded, labels, receiveTsNs);
            remember(streamName, recorded);
        } catch (Exception e) {
            metrics.increment("stream_recorder_errors_total", labels);
            metrics.increment("backend_parser_errors_total", labels);
            System.err.println("Failed to record tickerplant event from " + streamName + ": " + e.getMessage());
        }
    }

    private void observeEventQuality(String streamName, JsonNode event, Map<String, String> baseLabels, long consumerReceiveTsNs) {
        String eventType = event.path("event_type").asText("unknown");
        String schemaVersion = event.path("schema_version").asText("unknown");
        String source = event.path("metadata").path("source").asText("unknown");
        Map<String, String> labels = BackendMetrics.labels(
            "service", "stream_recorder",
            "stream", streamName,
            "event_type", eventType,
            "schema_version", schemaVersion,
            "source", source
        );
        metrics.increment("feature_module_events_in_total", labels);
        metrics.increment("backend_parser_messages_total", labels);
        if ("parser_error".equals(eventType)) {
            metrics.increment("backend_parser_errors_total", labels);
        }
        if ("sequence_gap".equals(eventType)) {
            metrics.increment("backend_orderbook_sequence_gap_total", labels);
        }
        if ("orderbook_snapshot".equals(eventType)) {
            metrics.increment("backend_orderbook_snapshot_total", labels);
        }
        if ("orderbook_delta".equals(eventType)) {
            metrics.increment("backend_orderbook_delta_total", labels);
        }
        long eventTsMs = event.path("metadata").path("event_ts_ms").asLong(0L);
        if (eventTsMs > 0L) {
            long ageMs = Math.max(0L, System.currentTimeMillis() - eventTsMs);
            metrics.observe("backend_ws_message_age_ms", baseLabels, ageMs);
            metrics.observe("feature_module_lag_ms", baseLabels, ageMs);
        }
        long ingressReceiveTsNs = event.path("metadata").path("ingest_ts_ns").asLong(0L);
        if (ingressReceiveTsNs > 0L && consumerReceiveTsNs >= ingressReceiveTsNs) {
            metrics.observe("tickerplant_stream_recorder_e2e_latency_ns", labels, consumerReceiveTsNs - ingressReceiveTsNs);
        }
    }

    private synchronized void remember(String streamName, JsonNode event) {
        Map<String, Object> recent = new LinkedHashMap<>();
        recent.put("recorded_at", Instant.now().toString());
        recent.put("stream", streamName);
        recent.put("event_id", event.path("event_id").asText(""));
        recent.put("event_type", event.path("event_type").asText(""));
        recent.put("market_ticker", event.path("metadata").path("market_ticker").asText(""));
        recentEvents.addLast(recent);
        while (recentEvents.size() > config.recentEventLimit()) {
            recentEvents.removeFirst();
        }
    }

    private void startHttp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        httpServer.createContext("/health", this::handleHealth);
        httpServer.createContext("/metrics", this::handleMetrics);
        httpServer.createContext("/events", this::handleEvents);
        httpServer.setExecutor(Executors.newFixedThreadPool(4));
        httpServer.start();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "ok");
        health.put("service", "tickerplant_stream_recorder");
        health.put("source_mode", "tickerplant_stream");
        health.put("channel", config.aeronChannel());
        health.put("output_root", config.outputRoot().toString());
        health.put("uptime_ms", System.currentTimeMillis() - startTimeMs);
        health.put("events_recorded", totalEvents.get());
        health.put("streams", config.streams().stream().map(StreamContract::streamName).toList());
        health.put("timestamp_source", config.timestampSource().metadata());
        writeJson(exchange, health);
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        write(exchange, "text/plain; charset=utf-8", metrics.prometheusText());
    }

    private synchronized void handleEvents(HttpExchange exchange) throws IOException {
        writeJson(exchange, Map.of("events", List.copyOf(recentEvents)));
    }

    private void writeJson(HttpExchange exchange, Object response) throws IOException {
        write(exchange, "application/json; charset=utf-8", mapper.writeValueAsString(response));
    }

    private void write(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
