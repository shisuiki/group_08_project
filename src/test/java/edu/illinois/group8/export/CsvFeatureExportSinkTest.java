package edu.illinois.group8.export;

import edu.illinois.group8.feature.FeatureOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvFeatureExportSinkTest {

    @Test
    void writesOneFilePerFeatureStream(@TempDir Path tempDir) throws IOException {
        List<String> warnings = new ArrayList<>();
        try (CsvFeatureExportSink sink = new CsvFeatureExportSink(tempDir, Set.of(), null, null, warnings::add)) {
            sink.write(output("feature.bbo", "MKT-1", 100L, "e1", Map.of("bid_price_micros", 5_000_000L)));
            sink.write(output("feature.ticker_snapshot", "MKT-1", 101L, "e2", Map.of("price_micros", 5_100_000L)));
            sink.write(output("feature.trade_tape", "MKT-1", 102L, "e3", Map.of("trade_id", "T-1")));
        }
        assertTrue(Files.exists(tempDir.resolve("feature.bbo.csv")));
        assertTrue(Files.exists(tempDir.resolve("feature.ticker_snapshot.csv")));
        assertTrue(Files.exists(tempDir.resolve("feature.trade_tape.csv")));
        assertTrue(warnings.isEmpty(), "no warnings expected: " + warnings);
    }

    @Test
    void headerIsUnionFromFirstRowAndExtraKeysDropWithOneWarning(@TempDir Path tempDir) throws IOException {
        List<String> warnings = new ArrayList<>();
        try (CsvFeatureExportSink sink = new CsvFeatureExportSink(tempDir, Set.of(), null, null, warnings::add)) {
            sink.write(output("feature.x", "MKT-1", 100L, "e1", ordered("a", 1L, "b", 2L)));
            sink.write(output("feature.x", "MKT-1", 101L, "e2", ordered("a", 3L, "b", 4L)));
            sink.write(output("feature.x", "MKT-1", 102L, "e3", ordered("a", 5L, "b", 6L, "c", 7L)));
            sink.write(output("feature.x", "MKT-1", 103L, "e4", ordered("a", 8L, "b", 9L, "c", 10L)));
            sink.write(output("feature.x", "MKT-1", 104L, "e5", ordered("a", 11L, "b", 12L)));
        }
        List<String> lines = Files.readAllLines(tempDir.resolve("feature.x.csv"), StandardCharsets.UTF_8);
        assertEquals(6, lines.size());
        assertEquals("feature_name,stream_name,market_ticker,event_ts_ms,source_event_id,a,b", lines.get(0));
        assertFalse(lines.get(0).contains(",c"), "header must not contain dropped column c");
        assertEquals("feature.x,feature.x,MKT-1,102,e3,5,6", lines.get(3));
        assertEquals(1, warnings.size(), "exactly one warning for missing column c");
        assertTrue(warnings.get(0).contains("c"));
    }

    @Test
    void marketFilterDiscardsNonMatchingRows(@TempDir Path tempDir) throws IOException {
        try (CsvFeatureExportSink sink = new CsvFeatureExportSink(
                tempDir, Set.of("MKT-KEEP"), null, null, msg -> {})) {
            sink.write(output("feature.x", "MKT-KEEP", 100L, "e1", Map.of("a", 1L)));
            sink.write(output("feature.x", "MKT-DROP", 101L, "e2", Map.of("a", 2L)));
            sink.write(output("feature.x", "MKT-KEEP", 102L, "e3", Map.of("a", 3L)));
        }
        List<String> lines = Files.readAllLines(tempDir.resolve("feature.x.csv"), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).contains("MKT-KEEP"));
        assertTrue(lines.get(2).contains("MKT-KEEP"));
        for (String line : lines) {
            assertFalse(line.contains("MKT-DROP"), "dropped market must not appear: " + line);
        }
    }

    @Test
    void timeWindowFilterDiscardsOutOfRangeRows(@TempDir Path tempDir) throws IOException {
        try (CsvFeatureExportSink sink = new CsvFeatureExportSink(
                tempDir, Set.of(), 100L, 200L, msg -> {})) {
            sink.write(output("feature.x", "MKT-1", 50L, "before", Map.of("a", 1L)));
            sink.write(output("feature.x", "MKT-1", 100L, "lower-edge", Map.of("a", 2L)));
            sink.write(output("feature.x", "MKT-1", 150L, "inside", Map.of("a", 3L)));
            sink.write(output("feature.x", "MKT-1", 200L, "upper-edge", Map.of("a", 4L)));
            sink.write(output("feature.x", "MKT-1", 201L, "after", Map.of("a", 5L)));
        }
        List<String> lines = Files.readAllLines(tempDir.resolve("feature.x.csv"), StandardCharsets.UTF_8);
        assertEquals(4, lines.size());
        assertTrue(lines.get(1).contains("lower-edge"));
        assertTrue(lines.get(2).contains("inside"));
        assertTrue(lines.get(3).contains("upper-edge"));
        for (String line : lines) {
            assertFalse(line.contains("before"));
            assertFalse(line.contains("after"));
        }
    }

    @Test
    void closeFlushesAndProducesWellFormedCsv(@TempDir Path tempDir) throws IOException {
        CsvFeatureExportSink sink = new CsvFeatureExportSink(tempDir, Set.of(), null, null, msg -> {});
        sink.write(output("feature.x", "MKT,WITH,COMMAS", 100L, "e\"1\"",
            Map.of("note", "hello, \"world\"")));
        sink.write(output("feature.y", "MKT-2", 101L, "e2", Map.of("v", 42L)));
        sink.close();

        Path x = tempDir.resolve("feature.x.csv");
        Path y = tempDir.resolve("feature.y.csv");
        assertTrue(Files.exists(x));
        assertTrue(Files.exists(y));

        List<String> xLines = Files.readAllLines(x, StandardCharsets.UTF_8);
        assertEquals(2, xLines.size());
        assertEquals("feature_name,stream_name,market_ticker,event_ts_ms,source_event_id,note", xLines.get(0));
        assertEquals(
            "feature.x,feature.x,\"MKT,WITH,COMMAS\",100,\"e\"\"1\"\"\",\"hello, \"\"world\"\"\"",
            xLines.get(1)
        );

        List<String> yLines = Files.readAllLines(y, StandardCharsets.UTF_8);
        assertEquals(2, yLines.size());
        assertEquals("feature.y,feature.y,MKT-2,101,e2,42", yLines.get(1));
    }

    private static FeatureOutput output(
        String featureName,
        String marketTicker,
        Long eventTsMs,
        String sourceEventId,
        Map<String, Object> values
    ) {
        return new FeatureOutput(featureName, featureName, marketTicker, eventTsMs, sourceEventId, values);
    }

    private static Map<String, Object> ordered(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
