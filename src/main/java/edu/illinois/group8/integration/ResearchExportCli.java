package edu.illinois.group8.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ResearchExportCli {
    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path journalRoot = Path.of(options.getOrDefault("journal-root", System.getenv().getOrDefault("BACKEND_JOURNAL_ROOT", "/app/journal")));
        Path outputDir = Path.of(options.getOrDefault("output", "research-export"));
        List<String> symbols = csv(options.getOrDefault("symbols", ""));
        Long fromMs = optionalTime(options.get("from"));
        Long toMs = optionalTime(options.get("to"));

        GatewayEventStore store = new GatewayEventStore(Integer.parseInt(options.getOrDefault("max-events", "500000")));
        long loaded = store.loadJournal(journalRoot);
        Files.createDirectories(outputDir);

        List<String> selected = symbols.isEmpty()
            ? store.symbols("").stream().map(item -> item.get("symbol").toString()).toList()
            : symbols;

        writeMetadata(outputDir, journalRoot, loaded, selected, fromMs, toMs);
        writeBars(outputDir.resolve("bars.csv"), store, selected, fromMs, toMs);
        writeTrades(outputDir.resolve("trades.csv"), store, selected, fromMs, toMs);
        writeOpenInterest(outputDir.resolve("open_interest.csv"), store, selected, fromMs, toMs);
        writeQuotes(outputDir.resolve("latest_quotes.csv"), store, selected);
        System.out.println("Exported " + selected.size() + " symbols to " + outputDir.toAbsolutePath());
    }

    private static void writeMetadata(Path outputDir, Path journalRoot, long loaded, List<String> symbols, Long fromMs, Long toMs)
        throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("{\n");
        body.append("  \"generated_at\": \"").append(Instant.now()).append("\",\n");
        body.append("  \"schema_version\": 1,\n");
        body.append("  \"journal_root\": \"").append(escape(journalRoot.toString())).append("\",\n");
        body.append("  \"loaded_events\": ").append(loaded).append(",\n");
        body.append("  \"price_scale\": \"probability_dollars\",\n");
        body.append("  \"from_ms\": ").append(fromMs).append(",\n");
        body.append("  \"to_ms\": ").append(toMs).append(",\n");
        body.append("  \"symbols\": [");
        for (int i = 0; i < symbols.size(); i++) {
            if (i > 0) {
                body.append(", ");
            }
            body.append('"').append(escape(symbols.get(i))).append('"');
        }
        body.append("]\n}\n");
        Files.writeString(outputDir.resolve("metadata.json"), body.toString(), StandardCharsets.UTF_8);
    }

    private static void writeBars(Path file, GatewayEventStore store, List<String> symbols, Long fromMs, Long toMs)
        throws IOException {
        StringBuilder csv = new StringBuilder("symbol,time_ms,time,open,high,low,close,volume\n");
        for (String symbol : symbols) {
            List<Map<String, Object>> bars = (List<Map<String, Object>>) store.history(symbol, "1", fromMs, toMs, 1_000_000).get("bars");
            for (Map<String, Object> bar : bars) {
                csv.append(symbol).append(',')
                    .append(bar.get("time_ms")).append(',')
                    .append(bar.get("time")).append(',')
                    .append(bar.get("open")).append(',')
                    .append(bar.get("high")).append(',')
                    .append(bar.get("low")).append(',')
                    .append(bar.get("close")).append(',')
                    .append(bar.get("volume")).append('\n');
            }
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
    }

    private static void writeTrades(Path file, GatewayEventStore store, List<String> symbols, Long fromMs, Long toMs)
        throws IOException {
        StringBuilder csv = new StringBuilder("symbol,time_ms,trade_id,yes_price,no_price,quantity,taker_side,event_id\n");
        for (String symbol : symbols) {
            List<Map<String, Object>> trades = (List<Map<String, Object>>) store.trades(symbol, fromMs, toMs, 1_000_000).get("trades");
            for (Map<String, Object> trade : trades) {
                csv.append(symbol).append(',')
                    .append(trade.get("time_ms")).append(',')
                    .append(escapeCsv(trade.get("trade_id"))).append(',')
                    .append(trade.get("yes_price")).append(',')
                    .append(trade.get("no_price")).append(',')
                    .append(trade.get("quantity")).append(',')
                    .append(escapeCsv(trade.get("taker_side"))).append(',')
                    .append(escapeCsv(trade.get("event_id"))).append('\n');
            }
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
    }

    private static void writeOpenInterest(Path file, GatewayEventStore store, List<String> symbols, Long fromMs, Long toMs)
        throws IOException {
        StringBuilder csv = new StringBuilder("symbol,time_ms,open_interest,dollar_open_interest\n");
        for (String symbol : symbols) {
            List<Map<String, Object>> points = (List<Map<String, Object>>) store.openInterest(symbol, fromMs, toMs, 1_000_000)
                .get("open_interest");
            for (Map<String, Object> point : points) {
                csv.append(symbol).append(',')
                    .append(point.get("time_ms")).append(',')
                    .append(point.get("open_interest")).append(',')
                    .append(point.get("dollar_open_interest")).append('\n');
            }
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
    }

    private static void writeQuotes(Path file, GatewayEventStore store, List<String> symbols) throws IOException {
        List<Map<String, Object>> quotes = (List<Map<String, Object>>) store.quotes(symbols).get("quotes");
        StringBuilder csv = new StringBuilder("symbol,price,bid,ask,bid_size,ask_size,volume,top_bid,top_ask,top_bid_size,top_ask_size,event_ts_ms\n");
        for (Map<String, Object> quote : quotes) {
            csv.append(quote.get("symbol")).append(',')
                .append(quote.get("price")).append(',')
                .append(quote.get("bid")).append(',')
                .append(quote.get("ask")).append(',')
                .append(quote.get("bid_size")).append(',')
                .append(quote.get("ask_size")).append(',')
                .append(quote.get("volume")).append(',')
                .append(quote.get("top_bid")).append(',')
                .append(quote.get("top_ask")).append(',')
                .append(quote.get("top_bid_size")).append(',')
                .append(quote.get("top_ask_size")).append(',')
                .append(quote.get("event_ts_ms")).append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            String[] parts = arg.substring(2).split("=", 2);
            options.put(parts[0], parts.length == 2 ? parts[1] : "true");
        }
        return options;
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private static Long optionalTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long value = Long.parseLong(raw);
        return value < 10_000_000_000L ? value * 1000L : value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeCsv(Object value) {
        if (value == null) {
            return "";
        }
        String raw = value.toString();
        if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")) {
            return '"' + raw.replace("\"", "\"\"") + '"';
        }
        return raw;
    }
}
