package edu.illinois.group8.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class StorageBackedRecordingConsumer implements AutoCloseable {
    private final Path journalRoot;
    private final GatewayEventStore store;
    private final GatewayWebSocketBroadcaster broadcaster;
    private final ObjectMapper mapper;
    private final long pollIntervalMs;
    private final Map<Path, Long> offsets = new ConcurrentHashMap<>();
    private final AtomicBoolean initialLoadComplete = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong scanCount = new AtomicLong();
    private final AtomicLong eventsRead = new AtomicLong();
    private final AtomicLong parseErrors = new AtomicLong();
    private final AtomicLong readErrors = new AtomicLong();
    private volatile Thread thread;
    private volatile long lastScanWallClockMs;

    public StorageBackedRecordingConsumer(
        Path journalRoot,
        GatewayEventStore store,
        GatewayWebSocketBroadcaster broadcaster,
        long pollIntervalMs
    ) {
        this.journalRoot = journalRoot;
        this.store = store;
        this.broadcaster = broadcaster;
        this.mapper = store.mapper();
        this.pollIntervalMs = pollIntervalMs;
    }

    public long loadInitial() {
        if (!initialLoadComplete.compareAndSet(false, true)) {
            return 0L;
        }
        return scan(false);
    }

    public long pollOnce() {
        return scan(true);
    }

    public void start() {
        loadInitial();
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::pollLoop, "frontend-adapter-recording-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("running", running.get());
        stats.put("journal_root", journalRoot.toString());
        stats.put("poll_interval_ms", pollIntervalMs);
        stats.put("files_tracked", offsets.size());
        stats.put("events_read", eventsRead.get());
        stats.put("scan_count", scanCount.get());
        stats.put("parse_errors", parseErrors.get());
        stats.put("read_errors", readErrors.get());
        stats.put("last_scan_wall_clock_ms", lastScanWallClockMs);
        return stats;
    }

    @Override
    public void close() {
        running.set(false);
        Thread local = thread;
        if (local != null) {
            local.interrupt();
            try {
                local.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                Thread.sleep(pollIntervalMs);
                scan(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                readErrors.incrementAndGet();
            }
        }
    }

    private long scan(boolean broadcastNewEvents) {
        scanCount.incrementAndGet();
        lastScanWallClockMs = System.currentTimeMillis();
        Path canonicalRoot = canonicalRoot();
        if (!Files.exists(canonicalRoot)) {
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(canonicalRoot)) {
            List<Path> files = paths
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".ndjson"))
                .sorted(Comparator.naturalOrder())
                .toList();
            long loaded = 0L;
            for (Path file : files) {
                loaded += readAppendedLines(file, broadcastNewEvents);
            }
            return loaded;
        } catch (IOException e) {
            readErrors.incrementAndGet();
            throw new IllegalStateException("Failed to scan recorder storage at " + canonicalRoot, e);
        }
    }

    private Path canonicalRoot() {
        Path downstreamRecorderRoot = journalRoot.resolve("canonical");
        if (Files.isDirectory(downstreamRecorderRoot)) {
            return downstreamRecorderRoot;
        }
        Path producerCanonicalRoot = journalRoot.resolve("producer-canonical");
        if (Files.isDirectory(producerCanonicalRoot)) {
            return producerCanonicalRoot;
        }
        return journalRoot;
    }

    private long readAppendedLines(Path file, boolean broadcastNewEvents) {
        try {
            long size = Files.size(file);
            long offset = offsets.getOrDefault(file, 0L);
            if (offset > size) {
                offset = 0L;
            }
            if (offset == size) {
                offsets.putIfAbsent(file, offset);
                return 0L;
            }

            long loaded = 0L;
            long position = offset;
            ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
            try (InputStream input = Files.newInputStream(file)) {
                input.skipNBytes(offset);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    for (int i = 0; i < read; i++) {
                        byte next = buffer[i];
                        position++;
                        if (next == '\n') {
                            loaded += processLine(file, line.toByteArray(), position, broadcastNewEvents);
                            line.reset();
                        } else {
                            line.write(next);
                        }
                    }
                }
            }
            return loaded;
        } catch (IOException e) {
            readErrors.incrementAndGet();
            return 0L;
        }
    }

    private long processLine(Path file, byte[] rawLine, long nextOffset, boolean broadcastNewEvents) {
        int length = rawLine.length;
        if (length > 0 && rawLine[length - 1] == '\r') {
            length--;
        }
        String line = new String(rawLine, 0, length, StandardCharsets.UTF_8);
        offsets.put(file, nextOffset);
        if (line.isBlank()) {
            return 0L;
        }
        var node = store.recordJson(line, "journal");
        if (node.isEmpty()) {
            parseErrors.incrementAndGet();
            return 0L;
        }
        eventsRead.incrementAndGet();
        if (broadcastNewEvents) {
            broadcast(node.get());
        }
        return 1L;
    }

    private void broadcast(JsonNode node) {
        try {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("type", "recorded_event");
            envelope.put("source", "storage_recorder");
            envelope.set("event", node);
            broadcaster.broadcast(mapper.writeValueAsString(envelope));
        } catch (IOException e) {
            readErrors.incrementAndGet();
        }
    }
}
