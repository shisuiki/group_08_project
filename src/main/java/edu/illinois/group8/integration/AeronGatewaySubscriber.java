package edu.illinois.group8.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.StreamContract;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class AeronGatewaySubscriber implements AutoCloseable {
    private final String channel;
    private final List<StreamContract> streams;
    private final GatewayEventStore store;
    private final GatewayWebSocketBroadcaster broadcaster;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong fragmentsRead = new AtomicLong();
    private MediaDriver mediaDriver;
    private Aeron aeron;
    private List<Subscription> subscriptions = List.of();
    private Thread thread;

    public AeronGatewaySubscriber(
        String channel,
        List<StreamContract> streams,
        GatewayEventStore store,
        GatewayWebSocketBroadcaster broadcaster,
        ObjectMapper mapper
    ) {
        this.channel = channel;
        this.streams = List.copyOf(streams);
        this.store = store;
        this.broadcaster = broadcaster;
        this.mapper = mapper;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .termBufferSparseFile(true));
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        subscriptions = streams.stream()
            .map(stream -> aeron.addSubscription(channel, stream.streamId()))
            .toList();
        thread = new Thread(this::poll, "frontend-adapter-aeron");
        thread.setDaemon(true);
        thread.start();
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("channel", channel);
        stats.put("running", running.get());
        stats.put("fragments_read", fragmentsRead.get());
        stats.put("streams", streams.stream().map(StreamContract::streamName).toList());
        return stats;
    }

    @Override
    public void close() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
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
                StreamContract stream = streams.get(i);
                fragments += subscription.poll((buffer, offset, length, header) -> {
                    byte[] data = new byte[length];
                    buffer.getBytes(offset, data);
                    String payload = new String(data, StandardCharsets.UTF_8);
                    store.recordJson(payload, "live").ifPresent(event -> broadcast(stream.streamName(), event));
                    fragmentsRead.incrementAndGet();
                }, 10);
            }
            if (fragments == 0) {
                Thread.onSpinWait();
            }
        }
    }

    private void broadcast(String streamName, Object event) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "live_event");
            envelope.put("stream_name", streamName);
            envelope.put("received_at", Instant.now().toString());
            envelope.put("event", event);
            broadcaster.broadcast(mapper.writeValueAsString(envelope));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to broadcast live event", e);
        }
    }
}
