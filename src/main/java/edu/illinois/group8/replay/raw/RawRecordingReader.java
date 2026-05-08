package edu.illinois.group8.replay.raw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class RawRecordingReader {
    private final Path root;
    private final ObjectMapper mapper;

    public RawRecordingReader(Path root) {
        this(root, new JsonCanonicalSerializer().mapper());
    }

    public RawRecordingReader(Path root, ObjectMapper mapper) {
        this.root = root;
        this.mapper = mapper;
    }

    public List<RawReplayEvent> read(long maxEvents) {
        if (!Files.exists(root)) {
            return List.of();
        }
        List<RawReplayEvent> events = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".ndjson"))
                .sorted(Comparator.naturalOrder())
                .toList();
            for (Path file : files) {
                readFile(file, events, maxEvents);
                if (maxEvents > 0L && events.size() >= maxEvents) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read raw recording root " + root, e);
        }
        events.sort(Comparator
            .comparing((RawReplayEvent event) -> event.receiveTsNs() == null ? Long.MAX_VALUE : event.receiveTsNs())
            .thenComparing(event -> event.sourceFile().toString())
            .thenComparingLong(RawReplayEvent::sourceLine));
        if (maxEvents > 0L && events.size() > maxEvents) {
            return List.copyOf(events.subList(0, (int) maxEvents));
        }
        return List.copyOf(events);
    }

    private void readFile(Path file, List<RawReplayEvent> events, long maxEvents) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0L;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                RawReplayEvent event = parse(file, lineNumber, line);
                if (event != null) {
                    events.add(event);
                    if (maxEvents > 0L && events.size() >= maxEvents) {
                        return;
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read raw recording file " + file, e);
        }
    }

    private RawReplayEvent parse(Path file, long lineNumber, String line) {
        try {
            JsonNode node = mapper.readTree(line);
            JsonNode rawPayload = node.path("raw_payload");
            if (rawPayload.isMissingNode() || rawPayload.isNull()) {
                return null;
            }
            Long receiveTsNs = number(node, "receive_ts_ns");
            if (receiveTsNs == null) {
                receiveTsNs = node.path("metadata").path("ingest_ts_ns").isNumber()
                    ? node.path("metadata").path("ingest_ts_ns").asLong()
                    : null;
            }
            return new RawReplayEvent(
                rawPayload.asText(),
                receiveTsNs,
                node.path("connection_id").asText(""),
                node.path("sequence").asLong(0L),
                file,
                lineNumber
            );
        } catch (IOException e) {
            throw new IllegalStateException("Malformed raw recording event in " + file + ":" + lineNumber, e);
        }
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }
}
