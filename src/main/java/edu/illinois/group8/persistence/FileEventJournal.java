package edu.illinois.group8.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.RawSourceEvent;
import edu.illinois.group8.metrics.BackendMetrics;

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
    private final JsonCanonicalSerializer serializer;
    private final BackendMetrics metrics;
    private final Set<String> appendedEventIds = new HashSet<>();

    public FileEventJournal(Path root) {
        this(root, new JsonCanonicalSerializer(), new BackendMetrics());
    }

    public FileEventJournal(Path root, JsonCanonicalSerializer serializer, BackendMetrics metrics) {
        this.root = root;
        this.serializer = serializer;
        this.metrics = metrics;
        try {
            Files.createDirectories(root);
            loadExistingEventIds();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize event journal at " + root, e);
        }
    }

    @Override
    public synchronized boolean appendRaw(RawSourceEvent event) {
        return append(event, root.resolve("raw").resolve(partitionFile(event)));
    }

    @Override
    public synchronized boolean appendCanonical(CanonicalEvent event) {
        String streamDirectory = event.streamName().replace('.', '_');
        return append(event, root.resolve("canonical").resolve(streamDirectory).resolve(partitionFile(event)));
    }

    private boolean append(CanonicalEvent event, Path file) {
        if (appendedEventIds.contains(event.eventId())) {
            metrics.increment("journal.idempotent_skip." + event.streamName());
            return false;
        }
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
            metrics.increment("journal.append." + event.streamName());
            return true;
        } catch (IOException e) {
            metrics.increment("journal.append_failed." + event.streamName());
            throw new IllegalStateException("Failed to append event " + event.eventId() + " to " + file, e);
        }
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
