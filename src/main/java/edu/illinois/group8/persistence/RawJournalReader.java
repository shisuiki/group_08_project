package edu.illinois.group8.persistence;

import com.fasterxml.jackson.databind.JsonNode;
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

public class RawJournalReader {
    private final Path journalRoot;
    private final JsonCanonicalSerializer serializer;

    public RawJournalReader(Path journalRoot) {
        this(journalRoot, new JsonCanonicalSerializer());
    }

    public RawJournalReader(Path journalRoot, JsonCanonicalSerializer serializer) {
        this.journalRoot = journalRoot;
        this.serializer = serializer;
    }

    public List<String> readRawPayloads() {
        Path rawRoot = journalRoot.resolve("raw");
        if (!Files.exists(rawRoot)) {
            return List.of();
        }
        List<String> payloads = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(rawRoot)) {
            List<Path> files = paths
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".ndjson"))
                .sorted(Comparator.naturalOrder())
                .toList();
            for (Path file : files) {
                readFile(file, payloads);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read raw journal from " + rawRoot, e);
        }
        return payloads;
    }

    private void readFile(Path file, List<String> payloads) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = serializer.mapper().readTree(line);
                JsonNode rawPayload = node.path("raw_payload");
                if (!rawPayload.isMissingNode()) {
                    payloads.add(rawPayload.asText());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read raw journal file " + file, e);
        }
    }
}
