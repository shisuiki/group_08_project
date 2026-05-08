package edu.illinois.group8.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.time.TimestampSource;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

public final class CanonicalRecordingWriter {
    private final Path outputRoot;
    private final String subtree;
    private final TimestampSource timestampSource;
    private final BackendMetrics metrics;
    private final StreamRecordingWriter.PartitionGranularity partitionGranularity;
    private final String serviceName;
    private final String metadataFieldName;
    private final String receiveTimestampFieldName;
    private final JsonCanonicalSerializer serializer;
    private final ObjectMapper mapper;

    public CanonicalRecordingWriter(
        Path outputRoot,
        String subtree,
        TimestampSource timestampSource,
        BackendMetrics metrics,
        StreamRecordingWriter.PartitionGranularity partitionGranularity,
        String serviceName,
        String metadataFieldName,
        String receiveTimestampFieldName
    ) {
        this.outputRoot = outputRoot;
        this.subtree = subtree == null ? "" : subtree.trim();
        this.timestampSource = timestampSource;
        this.metrics = metrics;
        this.partitionGranularity = partitionGranularity;
        this.serviceName = serviceName;
        this.metadataFieldName = metadataFieldName;
        this.receiveTimestampFieldName = receiveTimestampFieldName;
        this.serializer = new JsonCanonicalSerializer();
        this.mapper = serializer.mapper();
    }

    public JsonNode writeEvent(CanonicalEvent event, long receiveTsNs) throws IOException {
        return write(event.streamName(), serializer.toJson(event), receiveTsNs);
    }

    public synchronized JsonNode write(String streamName, String payload, long receiveTsNs) throws IOException {
        long storageEnqueueTsNs = timestampSource.nowNanos();
        JsonNode parsed = mapper.readTree(payload);
        ObjectNode recorded = parsed.deepCopy();
        long storageCommitStartTsNs = timestampSource.nowNanos();
        ObjectNode recorderMetadata = recorded.putObject(metadataFieldName);
        if (receiveTimestampFieldName != null && !receiveTimestampFieldName.isBlank()) {
            recorderMetadata.put(receiveTimestampFieldName, receiveTsNs);
        }
        recorderMetadata.put("storage_enqueue_ts_ns", storageEnqueueTsNs);
        recorderMetadata.put("storage_commit_start_ts_ns", storageCommitStartTsNs);
        recorderMetadata.put("storage_commit_wall_ts", Instant.now().toString());

        Path file = fileFor(streamName, parsed);
        Files.createDirectories(file.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(
            file,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )) {
            long storageCommitTsNs = timestampSource.nowNanos();
            recorderMetadata.put("storage_commit_ts_ns", storageCommitTsNs);
            writer.write(mapper.writeValueAsString(recorded));
            writer.newLine();
            long committedTsNs = timestampSource.nowNanos();
            Map<String, String> labels = BackendMetrics.labels("service", serviceName, "stream", streamName);
            metrics.increment("backend_storage_enqueue_total", labels);
            metrics.increment("backend_storage_commit_total", labels);
            metrics.observe("backend_storage_commit_latency_ns", labels, Math.max(0L, committedTsNs - storageCommitStartTsNs));
            return recorded;
        } catch (IOException e) {
            metrics.increment("backend_storage_error_total", BackendMetrics.labels("service", serviceName, "stream", streamName));
            throw e;
        }
    }

    private Path fileFor(String streamName, JsonNode event) {
        String streamDirectory = "stream=" + streamName;
        Long eventTsMs = event.path("metadata").path("event_ts_ms").isNumber()
            ? event.path("metadata").path("event_ts_ms").asLong()
            : null;
        var eventTime = eventTsMs == null
            ? Instant.now().atZone(ZoneOffset.UTC)
            : Instant.ofEpochMilli(eventTsMs).atZone(ZoneOffset.UTC);
        LocalDate date = eventTime.toLocalDate();
        String hour = String.format("%02d", eventTime.getHour());
        Path directory = rootDirectory()
            .resolve(streamDirectory)
            .resolve("date=" + date)
            .resolve("hour=" + hour);
        if (partitionGranularity == StreamRecordingWriter.PartitionGranularity.MINUTE) {
            directory = directory.resolve("minute=" + String.format("%02d", eventTime.getMinute()));
        }
        return directory.resolve("events.ndjson");
    }

    private Path rootDirectory() {
        return subtree.isBlank() ? outputRoot : outputRoot.resolve(subtree);
    }
}
