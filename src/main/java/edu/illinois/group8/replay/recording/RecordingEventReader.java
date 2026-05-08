package edu.illinois.group8.replay.recording;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.StreamContract;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class RecordingEventReader {
    private final Path recordingRoot;
    private final ObjectMapper mapper;

    public RecordingEventReader(Path recordingRoot) {
        this(recordingRoot, new JsonCanonicalSerializer().mapper());
    }

    public RecordingEventReader(Path recordingRoot, ObjectMapper mapper) {
        this.recordingRoot = recordingRoot;
        this.mapper = mapper;
    }

    public List<RecordingEvent> read(List<StreamContract> streams, long maxEvents) {
        Path canonicalRoot = canonicalRoot();
        if (!Files.exists(canonicalRoot)) {
            return List.of();
        }

        Set<String> selectedStreams = streams.stream().map(StreamContract::streamName).collect(java.util.stream.Collectors.toSet());
        Map<String, String> streamByDirectory = streams.stream()
            .collect(java.util.stream.Collectors.toMap(
                stream -> stream.streamName().replace('.', '_'),
                StreamContract::streamName
            ));
        List<RecordingEvent> events = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(canonicalRoot)) {
            List<Path> files = paths
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".ndjson"))
                .filter(path -> selectedStreams.contains(streamNameFromPath(canonicalRoot, path, streamByDirectory)))
                .sorted(Comparator.naturalOrder())
                .toList();
            for (Path file : files) {
                readFile(canonicalRoot, file, events, maxEvents, streamByDirectory);
                if (maxEvents > 0L && events.size() >= maxEvents) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read recorded events from " + canonicalRoot, e);
        }

        events.sort(Comparator
            .comparing((RecordingEvent event) -> event.eventTsMs() == null ? Long.MAX_VALUE : event.eventTsMs())
            .thenComparing(event -> event.recorderCommitTsNs() == null ? Long.MAX_VALUE : event.recorderCommitTsNs())
            .thenComparing(event -> event.sourceFile().toString())
            .thenComparingLong(RecordingEvent::sourceLine));
        if (maxEvents > 0L && events.size() > maxEvents) {
            return List.copyOf(events.subList(0, (int) maxEvents));
        }
        return List.copyOf(events);
    }

    private Path canonicalRoot() {
        if (Files.isDirectory(recordingRoot.resolve("canonical"))) {
            return recordingRoot.resolve("canonical");
        }
        if (Files.isDirectory(recordingRoot.resolve("producer-canonical"))) {
            return recordingRoot.resolve("producer-canonical");
        }
        return recordingRoot;
    }

    private void readFile(
        Path canonicalRoot,
        Path file,
        List<RecordingEvent> events,
        long maxEvents,
        Map<String, String> streamByDirectory
    ) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0L;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                events.add(parseEvent(canonicalRoot, file, lineNumber, line, streamByDirectory));
                if (maxEvents > 0L && events.size() >= maxEvents) {
                    return;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read recording file " + file, e);
        }
    }

    private RecordingEvent parseEvent(
        Path canonicalRoot,
        Path file,
        long lineNumber,
        String line,
        Map<String, String> streamByDirectory
    ) {
        try {
            JsonNode node = mapper.readTree(line);
            String streamName = node.path("stream_name").asText(streamNameFromPath(canonicalRoot, file, streamByDirectory));
            return new RecordingEvent(
                node.path("event_id").asText(""),
                node.path("event_type").asText("unknown"),
                streamName,
                node.path("metadata").path("event_ts_ms").isNumber() ? node.path("metadata").path("event_ts_ms").asLong() : null,
                node.path("recorder_metadata").path("storage_commit_ts_ns").isNumber()
                    ? node.path("recorder_metadata").path("storage_commit_ts_ns").asLong()
                    : null,
                file,
                lineNumber,
                line
            );
        } catch (IOException e) {
            throw new IllegalStateException("Malformed recording event in " + file + ":" + lineNumber, e);
        }
    }

    private String streamNameFromPath(Path canonicalRoot, Path file, Map<String, String> streamByDirectory) {
        Path relative = canonicalRoot.relativize(file);
        if (relative.getNameCount() == 0) {
            return "";
        }
        String directory = relative.getName(0).toString();
        if (directory.startsWith("stream=")) {
            return directory.substring("stream=".length());
        }
        return streamByDirectory.getOrDefault(directory, directory.replace('_', '.'));
    }
}
