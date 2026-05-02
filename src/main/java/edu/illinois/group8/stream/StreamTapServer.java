package edu.illinois.group8.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class StreamTapServer {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String channel;
    private final int port;
    private final int maxEvents;
    private final List<StreamContract> streams;
    private final ArrayDeque<Map<String, Object>> recentEvents = new ArrayDeque<>();
    private final Map<String, AtomicLong> countsByStream = new ConcurrentHashMap<>();
    private final AtomicLong totalEvents = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final long startTimeMs = System.currentTimeMillis();

    public StreamTapServer(String channel, int port, int maxEvents, List<StreamContract> streams) {
        this.channel = channel;
        this.port = port;
        this.maxEvents = maxEvents;
        this.streams = List.copyOf(streams);
        for (StreamContract stream : streams) {
            countsByStream.put(stream.streamName(), new AtomicLong());
        }
    }

    public static void main(String[] args) throws Exception {
        StreamTapServer server = fromEnvironment();
        server.start();
    }

    private static StreamTapServer fromEnvironment() {
        String channel = getenv("STREAM_TAP_CHANNEL",
            getenv("AERON_EXTERNAL_CHANNEL", getenv("AERON_CHANNEL", "aeron:udp?endpoint=224.0.1.1:40456")));
        int port = Integer.parseInt(getenv("STREAM_TAP_PORT", "8080"));
        int maxEvents = Integer.parseInt(getenv("STREAM_TAP_MAX_EVENTS", "200"));
        List<StreamContract> streams = resolveStreams(getenv("STREAM_TAP_STREAMS",
            "canonical.trade,canonical.ticker,canonical.open_interest,derived.top_of_book,system.sequence_gaps"));
        return new StreamTapServer(channel, port, maxEvents, streams);
    }

    private static List<StreamContract> resolveStreams(String raw) {
        List<StreamContract> streams = new ArrayList<>();
        for (String streamName : raw.split(",")) {
            String normalized = streamName.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            Optional<StreamContract> stream = StreamRegistry.byName(normalized);
            if (stream.isEmpty()) {
                throw new IllegalArgumentException("Unknown stream name: " + normalized);
            }
            streams.add(stream.get());
        }
        if (streams.isEmpty()) {
            throw new IllegalArgumentException("STREAM_TAP_STREAMS must include at least one stream.");
        }
        return streams;
    }

    private static String getenv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public void start() throws Exception {
        MediaDriver mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .termBufferSparseFile(true));
        Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        List<Subscription> subscriptions = streams.stream()
            .map(stream -> aeron.addSubscription(channel, stream.streamId()))
            .toList();

        Thread poller = new Thread(() -> poll(subscriptions), "stream-tap-poller");
        poller.setDaemon(true);
        poller.start();

        HttpServer httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        httpServer.createContext("/health", this::handleHealth);
        httpServer.createContext("/events", this::handleEvents);
        httpServer.createContext("/metrics", this::handleMetrics);
        httpServer.setExecutor(Executors.newFixedThreadPool(4));
        httpServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            httpServer.stop(1);
            subscriptions.forEach(Subscription::close);
            aeron.close();
            mediaDriver.close();
        }));

        System.out.println("StreamTap listening on :" + port + " channel=" + channel + " streams=" +
            streams.stream().map(StreamContract::streamName).toList());
        poller.join();
    }

    private void poll(List<Subscription> subscriptions) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int fragments = 0;
            for (int i = 0; i < subscriptions.size(); i++) {
                Subscription subscription = subscriptions.get(i);
                StreamContract stream = streams.get(i);
                fragments += subscription.poll((buffer, offset, length, header) -> {
                    byte[] data = new byte[length];
                    buffer.getBytes(offset, data);
                    String payload = new String(data, StandardCharsets.UTF_8);
                    record(stream.streamName(), payload);
                }, 10);
            }
            if (fragments == 0) {
                Thread.onSpinWait();
            }
        }
    }

    private synchronized void record(String streamName, String payload) {
        totalEvents.incrementAndGet();
        countsByStream.get(streamName).incrementAndGet();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("received_ts", Instant.now().toString());
        event.put("stream_name", streamName);
        event.put("payload", payload);
        recentEvents.addLast(event);
        while (recentEvents.size() > maxEvents) {
            recentEvents.removeFirst();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("channel", channel);
        response.put("uptime_ms", System.currentTimeMillis() - startTimeMs);
        response.put("total_events", totalEvents.get());
        response.put("streams", countsSnapshot());
        writeJson(exchange, response);
    }

    private synchronized void handleEvents(HttpExchange exchange) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total_events", totalEvents.get());
        response.put("events", List.copyOf(recentEvents));
        writeJson(exchange, response);
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("stream_tap_events_total ").append(totalEvents.get()).append('\n');
        countsSnapshot().forEach((stream, count) -> body
            .append("stream_tap_events_by_stream{stream=\"")
            .append(stream)
            .append("\"} ")
            .append(count)
            .append('\n'));
        write(exchange, "text/plain; charset=utf-8", body.toString());
    }

    private Map<String, Long> countsSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        streams.forEach(stream -> snapshot.put(stream.streamName(), countsByStream.get(stream.streamName()).get()));
        return snapshot;
    }

    private void writeJson(HttpExchange exchange, Object response) throws IOException {
        write(exchange, "application/json; charset=utf-8", OBJECT_MAPPER.writeValueAsString(response));
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
