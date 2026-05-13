package edu.illinois.group8.export;

import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.feature.FeatureOutputSink;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class CsvFeatureExportSink implements FeatureOutputSink {
    private static final List<String> CONSTANT_COLUMNS = List.of(
        "feature_name",
        "stream_name",
        "market_ticker",
        "event_ts_ms",
        "source_event_id"
    );

    private final Path outputDirectory;
    private final Set<String> marketFilter;
    private final Long fromTsMs;
    private final Long toTsMs;
    private final Consumer<String> warningSink;
    private final Map<String, FeatureWriter> writers = new HashMap<>();
    private boolean closed;

    public CsvFeatureExportSink(Path outputDirectory) {
        this(outputDirectory, Set.of(), null, null, msg -> System.err.println("CsvFeatureExportSink: " + msg));
    }

    public CsvFeatureExportSink(
        Path outputDirectory,
        Set<String> marketFilter,
        Long fromTsMs,
        Long toTsMs,
        Consumer<String> warningSink
    ) {
        this.outputDirectory = outputDirectory;
        this.marketFilter = marketFilter == null ? Set.of() : Set.copyOf(marketFilter);
        this.fromTsMs = fromTsMs;
        this.toTsMs = toTsMs;
        this.warningSink = warningSink == null ? msg -> {} : warningSink;
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create output directory " + outputDirectory, e);
        }
    }

    @Override
    public void write(FeatureOutput output) {
        if (closed) {
            throw new IllegalStateException("CsvFeatureExportSink already closed");
        }
        if (!matchesMarket(output) || !matchesWindow(output)) {
            return;
        }
        FeatureWriter writer = writers.computeIfAbsent(output.featureName(), this::openWriter);
        writer.writeRow(output);
    }

    public Map<String, Long> rowCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, FeatureWriter> entry : writers.entrySet()) {
            result.put(entry.getKey(), entry.getValue().rows);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        IOException first = null;
        for (FeatureWriter writer : writers.values()) {
            try {
                writer.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw new UncheckedIOException("Failed to close CSV writer(s)", first);
        }
    }

    private boolean matchesMarket(FeatureOutput output) {
        if (marketFilter.isEmpty()) {
            return true;
        }
        return output.marketTicker() != null && marketFilter.contains(output.marketTicker());
    }

    private boolean matchesWindow(FeatureOutput output) {
        if (fromTsMs == null && toTsMs == null) {
            return true;
        }
        Long ts = output.eventTsMs();
        if (ts == null) {
            return false;
        }
        if (fromTsMs != null && ts < fromTsMs) {
            return false;
        }
        if (toTsMs != null && ts > toTsMs) {
            return false;
        }
        return true;
    }

    private FeatureWriter openWriter(String featureName) {
        Path file = outputDirectory.resolve(featureName + ".csv");
        try {
            BufferedWriter buffered = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            return new FeatureWriter(featureName, buffered);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open CSV writer for " + featureName, e);
        }
    }

    private final class FeatureWriter {
        private final String featureName;
        private final BufferedWriter writer;
        private final Set<String> warnedKeys = new HashSet<>();
        private List<String> valueColumns;
        private long rows;

        FeatureWriter(String featureName, BufferedWriter writer) {
            this.featureName = featureName;
            this.writer = writer;
        }

        void writeRow(FeatureOutput output) {
            try {
                if (valueColumns == null) {
                    valueColumns = new ArrayList<>(new LinkedHashSet<>(output.values().keySet()));
                    Collections.sort(valueColumns);
                    writeHeader();
                }
                writeData(output);
                rows++;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed writing CSV row for " + featureName, e);
            }
        }

        private void writeHeader() throws IOException {
            List<String> header = new ArrayList<>(CONSTANT_COLUMNS.size() + valueColumns.size());
            header.addAll(CONSTANT_COLUMNS);
            header.addAll(valueColumns);
            writer.write(joinCsv(header));
            writer.newLine();
        }

        private void writeData(FeatureOutput output) throws IOException {
            List<String> row = new ArrayList<>(CONSTANT_COLUMNS.size() + valueColumns.size());
            row.add(escape(output.featureName()));
            row.add(escape(output.streamName()));
            row.add(escape(output.marketTicker()));
            row.add(output.eventTsMs() == null ? "" : Long.toString(output.eventTsMs()));
            row.add(escape(output.sourceEventId()));
            for (String column : valueColumns) {
                Object value = output.values().get(column);
                row.add(formatValue(value));
            }
            for (String key : output.values().keySet()) {
                if (!valueColumns.contains(key) && warnedKeys.add(key)) {
                    warningSink.accept(
                        "feature " + featureName + " produced unknown column '" + key
                            + "'; skipping for remainder of file"
                    );
                }
            }
            writer.write(String.join(",", row));
            writer.newLine();
        }

        void close() throws IOException {
            writer.flush();
            writer.close();
        }
    }

    private static String joinCsv(List<String> columns) {
        List<String> escaped = new ArrayList<>(columns.size());
        for (String column : columns) {
            escaped.add(escape(column));
        }
        return String.join(",", escaped);
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return value.toString();
        }
        if (value instanceof Double || value instanceof Float) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        return escape(value.toString());
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                needsQuote = true;
                break;
            }
        }
        if (!needsQuote) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append('"').append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
