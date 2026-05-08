package edu.illinois.group8.recorder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class RawIngestRecorder implements AutoCloseable {
    private static volatile RawIngestRecorder INSTANCE;

    private final RawIngestRecorderConfig config;
    private final LinkedBlockingQueue<Record> queue;
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong connectionSequence = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private Thread writerThread;

    private RawIngestRecorder(RawIngestRecorderConfig config) {
        this.config = config;
        this.queue = new LinkedBlockingQueue<>(config.queueCapacity());
        if (config.enabled()) {
            start();
        }
    }

    public static RawIngestRecorder fromEnvironment() {
        RawIngestRecorder local = INSTANCE;
        if (local == null) {
            synchronized (RawIngestRecorder.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new RawIngestRecorder(RawIngestRecorderConfig.fromEnvironment());
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    public boolean enabled() {
        return config.enabled();
    }

    public String newConnectionId() {
        return config.captureId() + "-" + connectionSequence.incrementAndGet();
    }

    public void recordInbound(String connectionId, String rawPayload) {
        recordInbound(connectionId, rawPayload, config.timestampSource().nowNanos(), Instant.now());
    }

    public void recordInbound(String connectionId, String rawPayload, long receiveTsNs, Instant receiveWallTs) {
        if (!config.enabled()) {
            return;
        }
        Record record = new Record(
            connectionId,
            sequence.incrementAndGet(),
            receiveTsNs,
            receiveWallTs,
            rawPayload
        );
        if (config.dropOnFull()) {
            if (!queue.offer(record)) {
                long count = dropped.incrementAndGet();
                if (count == 1L || count % 1000L == 0L) {
                    System.err.println("Raw ingest recorder dropped " + count + " messages because the queue is full.");
                }
            }
            return;
        }
        try {
            queue.put(record);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dropped.incrementAndGet();
        }
    }

    @Override
    public void close() {
        running.set(false);
        Thread local = writerThread;
        if (local != null) {
            local.interrupt();
            try {
                local.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.createDirectories(config.outputRoot());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize raw ingest recorder at " + config.outputRoot(), e);
        }
        writerThread = new Thread(this::writeLoop, "raw-ingest-recorder");
        writerThread.setDaemon(true);
        writerThread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        System.out.println("RawIngestRecorder enabled root=" + config.outputRoot()
            + " source=" + config.source()
            + " queue_capacity=" + config.queueCapacity()
            + " drop_on_full=" + config.dropOnFull());
    }

    private void writeLoop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                Record record = queue.poll(250L, TimeUnit.MILLISECONDS);
                if (record != null) {
                    write(record);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("Raw ingest recorder write failed: " + e.getMessage());
            }
        }
    }

    private void write(Record record) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("schema_version", 1);
        node.put("record_type", "raw_websocket_message");
        node.put("source", config.source());
        node.put("direction", "inbound");
        node.put("capture_id", config.captureId());
        node.put("connection_id", record.connectionId());
        node.put("sequence", record.sequence());
        node.put("receive_ts_ns", record.receiveTsNs());
        node.put("receive_wall_ts", record.receiveWallTs().toString());
        node.put("payload_sha256", sha256(record.rawPayload()));
        node.put("raw_payload", record.rawPayload());

        Path file = fileFor(record.receiveWallTs());
        Files.createDirectories(file.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(
            file,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )) {
            writer.write(mapper.writeValueAsString(node));
            writer.newLine();
        }
    }

    private Path fileFor(Instant wallTs) {
        var eventTime = wallTs.atZone(ZoneOffset.UTC);
        LocalDate date = eventTime.toLocalDate();
        String hour = String.format("%02d", eventTime.getHour());
        Path directory = config.outputRoot()
            .resolve("source=" + config.source())
            .resolve("date=" + date)
            .resolve("hour=" + hour);
        if (config.partitionGranularity() == StreamRecordingWriter.PartitionGranularity.MINUTE) {
            directory = directory.resolve("minute=" + String.format("%02d", eventTime.getMinute()));
        }
        return directory.resolve("events.ndjson");
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private record Record(
        String connectionId,
        long sequence,
        long receiveTsNs,
        Instant receiveWallTs,
        String rawPayload
    ) {
    }
}
