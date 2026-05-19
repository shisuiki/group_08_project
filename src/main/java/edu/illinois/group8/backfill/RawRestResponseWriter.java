package edu.illinois.group8.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.recorder.StreamRecordingWriter;

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

final class RawRestResponseWriter implements RawRestBackfillSink {
    private final Path outputRoot;
    private final StreamRecordingWriter.PartitionGranularity partitionGranularity;
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();

    RawRestResponseWriter(Path outputRoot, StreamRecordingWriter.PartitionGranularity partitionGranularity) {
        this.outputRoot = outputRoot;
        this.partitionGranularity = partitionGranularity;
    }

    @Override
    public void write(String endpoint, String ticker, String rawPayload, long fetchTsNs, Instant fetchWallTs) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("schema_version", 1);
        node.put("record_type", "raw_rest_response");
        node.put("source", "kalshi.rest");
        node.put("endpoint", endpoint);
        if (ticker != null && !ticker.isBlank()) {
            node.put("ticker", ticker);
        }
        node.put("fetch_ts_ns", fetchTsNs);
        node.put("fetch_wall_ts", fetchWallTs.toString());
        node.put("payload_sha256", sha256(rawPayload));
        node.put("raw_payload", rawPayload);

        Path file = fileFor(endpoint, fetchWallTs);
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

    private Path fileFor(String endpoint, Instant fetchWallTs) {
        var eventTime = fetchWallTs.atZone(ZoneOffset.UTC);
        LocalDate date = eventTime.toLocalDate();
        Path directory = outputRoot
            .resolve("endpoint=" + endpoint.replace('.', '_'))
            .resolve("date=" + date)
            .resolve("hour=" + String.format("%02d", eventTime.getHour()));
        if (partitionGranularity == StreamRecordingWriter.PartitionGranularity.MINUTE) {
            directory = directory.resolve("minute=" + String.format("%02d", eventTime.getMinute()));
        }
        return directory.resolve("responses.ndjson");
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
