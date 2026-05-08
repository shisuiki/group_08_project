package edu.illinois.group8.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.RawSourceEvent;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.recorder.StreamRecordingWriter;
import edu.illinois.group8.time.TimestampSource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

public class FileEventJournal implements EventJournal {
    private final Path root;
    private final boolean legacyJournalEnabled;
    private final Path rawRecordingRoot;
    private final Path canonicalRecordingRoot;
    private final JsonCanonicalSerializer serializer;
    private final BackendMetrics metrics;
    private final TimestampSource timestampSource;
    private final StreamRecordingWriter.PartitionGranularity recordingPartitionGranularity;
    private final Set<String> appendedEventIds = new HashSet<>();

    public FileEventJournal(Path root) {
        this(root, new JsonCanonicalSerializer(), new BackendMetrics());
    }

    public FileEventJournal(Path root, JsonCanonicalSerializer serializer, BackendMetrics metrics) {
        this(root, serializer, metrics, true, null, null, TimestampSource.fromEnvironment(), StreamRecordingWriter.PartitionGranularity.HOUR);
    }

    public FileEventJournal(
        Path root,
        JsonCanonicalSerializer serializer,
        BackendMetrics metrics,
        boolean legacyJournalEnabled,
        Path rawRecordingRoot,
        Path canonicalRecordingRoot,
        TimestampSource timestampSource,
        StreamRecordingWriter.PartitionGranularity recordingPartitionGranularity
    ) {
        this.root = root;
        this.legacyJournalEnabled = legacyJournalEnabled;
        this.rawRecordingRoot = rawRecordingRoot;
        this.canonicalRecordingRoot = canonicalRecordingRoot;
        this.serializer = serializer;
        this.metrics = metrics;
        this.timestampSource = timestampSource;
        this.recordingPartitionGranularity = recordingPartitionGranularity;
        try {
            if (legacyJournalEnabled) {
                Files.createDirectories(root);
            }
            if (rawRecordingRoot != null) {
                Files.createDirectories(rawRecordingRoot);
            }
            if (canonicalRecordingRoot != null) {
                Files.createDirectories(canonicalRecordingRoot);
            }
            if (legacyJournalEnabled) {
                loadExistingEventIds();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize event journal at " + root, e);
        }
    }

    @Override
    public synchronized boolean appendRaw(RawSourceEvent event) {
        boolean appended = appendLegacy(event, root.resolve("raw").resolve(partitionFile(event)));
        if (appended && rawRecordingRoot != null) {
            appendRecorded(event, rawRecordingRoot, event.streamName(), "producer_raw_journal");
        }
        return appended;
    }

    @Override
    public synchronized boolean appendCanonical(CanonicalEvent event) {
        String streamDirectory = event.streamName().replace('.', '_');
        boolean appended = appendLegacy(event, root.resolve("canonical").resolve(streamDirectory).resolve(partitionFile(event)));
        if (appended && canonicalRecordingRoot != null) {
            appendRecorded(event, canonicalRecordingRoot, event.streamName(), "producer_canonical_journal");
        }
        return appended;
    }

    private boolean appendLegacy(CanonicalEvent event, Path file) {
        if (!legacyJournalEnabled) {
            return true;
        }
        return append(event, file);
    }

    private boolean append(CanonicalEvent event, Path file) {
        if (appendedEventIds.contains(event.eventId())) {
            metrics.increment("journal.idempotent_skip." + event.streamName());
            return false;
        }
        var labels = BackendMetrics.labels("service", "backend_journal", "stream", event.streamName());
        long enqueueTsNs = System.nanoTime();
        metrics.increment("backend_storage_enqueue_total", labels);
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )) {
                writer.write(serializer.toJson(event));
                writer.newLine();
            }
            appendedEventIds.add(event.eventId());
            metrics.increment("backend_storage_commit_total", labels);
            metrics.observe("backend_storage_commit_latency_ns", labels, Math.max(0L, System.nanoTime() - enqueueTsNs));
            metrics.increment("journal.append." + event.streamName());
            return true;
        } catch (IOException e) {
            metrics.increment("backend_storage_error_total", labels);
            metrics.increment("journal.append_failed." + event.streamName());
            throw new IllegalStateException("Failed to append event " + event.eventId() + " to " + file, e);
        }
    }

    private void appendRecorded(CanonicalEvent event, Path outputRoot, String streamName, String service) {
        var labels = BackendMetrics.labels("service", service, "stream", streamName);
        long enqueueTsNs = timestampSource.nowNanos();
        metrics.increment("backend_storage_enqueue_total", labels);
        try {
            Path file = recordingFile(outputRoot, streamName, event);
            Files.createDirectories(file.getParent());
            ObjectNode recorded = (ObjectNode) serializer.mapper().readTree(serializer.toJson(event));
            ObjectNode metadata = recorded.putObject("producer_journal_metadata");
            metadata.put("producer_storage_enqueue_ts_ns", enqueueTsNs);
            metadata.put("producer_storage_commit_start_ts_ns", timestampSource.nowNanos());
            metadata.put("producer_storage_commit_wall_ts", Instant.now().toString());
            try (BufferedWriter writer = Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )) {
                long commitTsNs = timestampSource.nowNanos();
                metadata.put("producer_storage_commit_ts_ns", commitTsNs);
                writer.write(serializer.mapper().writeValueAsString(recorded));
                writer.newLine();
            }
            metrics.increment("backend_storage_commit_total", labels);
            metrics.observe("backend_storage_commit_latency_ns", labels, Math.max(0L, timestampSource.nowNanos() - enqueueTsNs));
        } catch (IOException e) {
            metrics.increment("backend_storage_error_total", labels);
            throw new IllegalStateException("Failed to append producer recording event " + event.eventId(), e);
        }
    }

    private Path recordingFile(Path outputRoot, String streamName, CanonicalEvent event) {
        Long eventTsMs = event.metadata().eventTsMs();
        var eventTime = eventTsMs == null
            ? Instant.now().atZone(ZoneOffset.UTC)
            : Instant.ofEpochMilli(eventTsMs).atZone(ZoneOffset.UTC);
        LocalDate date = eventTime.toLocalDate();
        String hour = String.format("%02d", eventTime.getHour());
        Path directory = outputRoot.resolve("stream=" + streamName)
            .resolve("date=" + date)
            .resolve("hour=" + hour);
        if (recordingPartitionGranularity == StreamRecordingWriter.PartitionGranularity.MINUTE) {
            directory = directory.resolve("minute=" + String.format("%02d", eventTime.getMinute()));
        }
        return directory.resolve("events.ndjson");
    }

    private String partitionFile(CanonicalEvent event) {
        Long eventTsMs = event.metadata().eventTsMs();
        LocalDate date = eventTsMs == null
            ? LocalDate.now(ZoneOffset.UTC)
            : Instant.ofEpochMilli(eventTsMs).atZone(ZoneOffset.UTC).toLocalDate();
        return date + ".ndjson";
    }

    private void loadExistingEventIds() throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".ndjson"))
                .forEach(this::loadEventIdsFromFile);
        }
    }

    private void loadEventIdsFromFile(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = serializer.mapper().readTree(line);
                JsonNode eventId = node.path("event_id");
                if (!eventId.isMissingNode()) {
                    appendedEventIds.add(eventId.asText());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load event ids from " + file, e);
        }
    }
}
