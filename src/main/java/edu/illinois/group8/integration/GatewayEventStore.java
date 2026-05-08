package edu.illinois.group8.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class GatewayEventStore {
    private static final long PRICE_SCALE = 1_000_000L;
    private static final long QUANTITY_SCALE = 1_000_000L;

    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();
    private final Map<String, SymbolState> symbols = new ConcurrentHashMap<>();
    private final ArrayDeque<JsonNode> indexedEvents = new ArrayDeque<>();
    private final int maxIndexedEvents;
    private final AtomicLong totalEvents = new AtomicLong();
    private final AtomicLong journalEvents = new AtomicLong();
    private final AtomicLong liveEvents = new AtomicLong();
    private volatile long lastEventWallClockMs;

    public GatewayEventStore(int maxIndexedEvents) {
        this.maxIndexedEvents = maxIndexedEvents;
    }

    public long loadJournal(Path journalRoot) {
        Path canonicalRoot = journalRoot.resolve("canonical");
        if (!Files.exists(canonicalRoot)) {
            return 0L;
        }
        long before = journalEvents.get();
        try (Stream<Path> paths = Files.walk(canonicalRoot)) {
            List<Path> files = paths
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".ndjson"))
                .sorted(Comparator.naturalOrder())
                .toList();
            for (Path file : files) {
                loadFile(file);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load canonical journal from " + canonicalRoot, e);
        }
        return journalEvents.get() - before;
    }

    public Optional<JsonNode> recordJson(String payload, String source) {
        try {
            JsonNode node = mapper.readTree(payload);
            record(node, source);
            return Optional.of(node);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void record(JsonNode node, String source) {
        String symbol = node.path("metadata").path("market_ticker").asText("");
        if (symbol.isBlank()) {
            return;
        }

        SymbolState state = symbols.computeIfAbsent(symbol, SymbolState::new);
        long eventTsMs = eventTsMs(node);
        synchronized (state) {
            state.record(node, eventTsMs);
        }
        synchronized (indexedEvents) {
            indexedEvents.addLast(node);
            while (indexedEvents.size() > maxIndexedEvents) {
                indexedEvents.removeFirst();
            }
        }
        totalEvents.incrementAndGet();
        if ("journal".equals(source)) {
            journalEvents.incrementAndGet();
        } else {
            liveEvents.incrementAndGet();
        }
        lastEventWallClockMs = System.currentTimeMillis();
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("symbols", symbols.size());
        stats.put("total_events", totalEvents.get());
        stats.put("journal_events", journalEvents.get());
        stats.put("live_events", liveEvents.get());
        stats.put("indexed_events", indexedEvents.size());
        stats.put("last_event_wall_clock_ms", lastEventWallClockMs);
        return stats;
    }

    public List<Map<String, Object>> symbols(String query) {
        String normalized = query == null ? "" : query.trim().toUpperCase();
        return symbols.values().stream()
            .filter(state -> normalized.isBlank() || state.ticker.toUpperCase().contains(normalized))
            .sorted(Comparator.comparing(state -> state.ticker))
            .map(SymbolState::metadata)
            .toList();
    }

    public Optional<Map<String, Object>> symbol(String ticker) {
        SymbolState state = symbols.get(ticker);
        if (state == null) {
            return Optional.empty();
        }
        synchronized (state) {
            return Optional.of(state.metadata());
        }
    }

    public Map<String, Object> history(String ticker, String resolution, Long fromMs, Long toMs, int limit) {
        SymbolState state = symbols.get(ticker);
        long bucketMs = resolutionToMillis(resolution);
        List<Map<String, Object>> bars = state == null
            ? List.of()
            : state.bars(bucketMs, fromMs, toMs, limit);
        Map<String, Object> response = envelope("history");
        response.put("symbol", ticker);
        response.put("resolution", resolution);
        response.put("price_scale", "probability_dollars");
        response.put("bars", bars);
        response.put("s", "ok");
        return response;
    }

    public Map<String, Object> tradingViewHistory(String ticker, String resolution, Long fromMs, Long toMs, int limit) {
        List<Map<String, Object>> bars = (List<Map<String, Object>>) history(ticker, resolution, fromMs, toMs, limit).get("bars");
        Map<String, Object> response = new LinkedHashMap<>();
        if (bars.isEmpty()) {
            response.put("s", "no_data");
            response.put("nextTime", null);
            return response;
        }
        response.put("s", "ok");
        response.put("t", bars.stream().map(bar -> ((Number) bar.get("time_ms")).longValue() / 1000L).toList());
        response.put("o", bars.stream().map(bar -> bar.get("open")).toList());
        response.put("h", bars.stream().map(bar -> bar.get("high")).toList());
        response.put("l", bars.stream().map(bar -> bar.get("low")).toList());
        response.put("c", bars.stream().map(bar -> bar.get("close")).toList());
        response.put("v", bars.stream().map(bar -> bar.get("volume")).toList());
        return response;
    }

    public Map<String, Object> quotes(List<String> tickers) {
        Map<String, Object> response = envelope("quotes");
        List<Map<String, Object>> quotes = tickers.stream()
            .map(symbols::get)
            .filter(state -> state != null)
            .map(state -> {
                synchronized (state) {
                    return state.quote();
                }
            })
            .toList();
        response.put("quotes", quotes);
        return response;
    }

    public Map<String, Object> depth(String ticker) {
        Map<String, Object> response = envelope("depth");
        response.put("symbol", ticker);
        SymbolState state = symbols.get(ticker);
        if (state == null) {
            response.put("depth", null);
            return response;
        }
        synchronized (state) {
            response.put("depth", state.depth());
        }
        return response;
    }

    public Map<String, Object> trades(String ticker, Long fromMs, Long toMs, int limit) {
        Map<String, Object> response = envelope("trades");
        response.put("symbol", ticker);
        SymbolState state = symbols.get(ticker);
        response.put("trades", state == null ? List.of() : state.trades(fromMs, toMs, limit));
        return response;
    }

    public Map<String, Object> openInterest(String ticker, Long fromMs, Long toMs, int limit) {
        Map<String, Object> response = envelope("open_interest");
        response.put("symbol", ticker);
        SymbolState state = symbols.get(ticker);
        response.put("open_interest", state == null ? List.of() : state.openInterest(fromMs, toMs, limit));
        return response;
    }

    public List<JsonNode> eventsForReplay(List<String> marketTickers, Long fromMs, Long toMs) {
        List<String> markets = marketTickers == null ? List.of() : marketTickers;
        synchronized (indexedEvents) {
            return indexedEvents.stream()
                .filter(event -> markets.isEmpty() || markets.contains(event.path("metadata").path("market_ticker").asText()))
                .filter(event -> within(eventTsMs(event), fromMs, toMs))
                .sorted(Comparator.comparingLong(GatewayEventStore::eventTsMs))
                .toList();
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    private void loadFile(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    recordJson(line, "journal");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load canonical journal file " + file, e);
        }
    }

    private static Map<String, Object> envelope(String dataSet) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schema_version", 1);
        response.put("dataset", dataSet);
        response.put("generated_at", Instant.now().toString());
        return response;
    }

    static long parseTimeParam(String raw, Long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue == null ? 0L : defaultValue;
        }
        long value = Long.parseLong(raw);
        return value < 10_000_000_000L ? value * 1000L : value;
    }

    static long resolutionToMillis(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return 60_000L;
        }
        String normalized = resolution.trim().toUpperCase();
        if (normalized.endsWith("S")) {
            return Long.parseLong(normalized.substring(0, normalized.length() - 1)) * 1000L;
        }
        if (normalized.endsWith("H")) {
            return Long.parseLong(normalized.substring(0, normalized.length() - 1)) * 3_600_000L;
        }
        if ("D".equals(normalized) || "1D".equals(normalized)) {
            return 86_400_000L;
        }
        return Long.parseLong(normalized) * 60_000L;
    }

    static long eventTsMs(JsonNode node) {
        JsonNode value = node.path("metadata").path("event_ts_ms");
        if (value.isNumber()) {
            return value.asLong();
        }
        return 0L;
    }

    private static boolean within(long eventTsMs, Long fromMs, Long toMs) {
        if (eventTsMs == 0L) {
            return true;
        }
        if (fromMs != null && eventTsMs < fromMs) {
            return false;
        }
        return toMs == null || eventTsMs <= toMs;
    }

    private static Double microsToDecimal(JsonNode node) {
        if (!node.isNumber()) {
            return null;
        }
        return BigDecimal.valueOf(node.asLong())
            .divide(BigDecimal.valueOf(PRICE_SCALE), 6, RoundingMode.HALF_UP)
            .doubleValue();
    }

    private static Double quantityToDecimal(JsonNode node) {
        if (!node.isNumber()) {
            return null;
        }
        return BigDecimal.valueOf(node.asLong())
            .divide(BigDecimal.valueOf(QUANTITY_SCALE), 6, RoundingMode.HALF_UP)
            .doubleValue();
    }

    private static final class SymbolState {
        private final String ticker;
        private final List<PricePoint> prices = new ArrayList<>();
        private final List<Map<String, Object>> trades = new ArrayList<>();
        private final List<Map<String, Object>> openInterest = new ArrayList<>();
        private final AtomicLong eventCount = new AtomicLong();
        private String marketId;
        private long firstEventTsMs;
        private long lastEventTsMs;
        private JsonNode latestTicker;
        private JsonNode latestTopOfBook;
        private JsonNode latestDepth;

        private SymbolState(String ticker) {
            this.ticker = ticker;
        }

        private void record(JsonNode event, long eventTsMs) {
            eventCount.incrementAndGet();
            JsonNode metadata = event.path("metadata");
            if (marketId == null || marketId.isBlank()) {
                marketId = metadata.path("market_id").asText("");
            }
            if (eventTsMs > 0L) {
                firstEventTsMs = firstEventTsMs == 0L ? eventTsMs : Math.min(firstEventTsMs, eventTsMs);
                lastEventTsMs = Math.max(lastEventTsMs, eventTsMs);
            }

            String eventType = event.path("event_type").asText();
            switch (eventType) {
                case "market_trade" -> recordTrade(event, eventTsMs);
                case "ticker_update" -> recordTicker(event, eventTsMs);
                case "open_interest_update" -> recordOpenInterest(event, eventTsMs);
                case "top_of_book_update" -> latestTopOfBook = event;
                case "orderbook_snapshot", "orderbook_delta" -> latestDepth = event;
                default -> {
                }
            }
        }

        private void recordTicker(JsonNode event, long eventTsMs) {
            latestTicker = event;
            Double price = microsToDecimal(event.path("price_micros"));
            if (price == null) {
                Double bid = microsToDecimal(event.path("yes_bid_micros"));
                Double ask = microsToDecimal(event.path("yes_ask_micros"));
                if (bid != null && ask != null) {
                    price = (bid + ask) / 2.0;
                }
            }
            if (eventTsMs > 0L && price != null) {
                prices.add(new PricePoint(eventTsMs, price, 0.0));
            }
        }

        private void recordTrade(JsonNode event, long eventTsMs) {
            Double price = microsToDecimal(event.path("yes_price_micros"));
            Double quantity = quantityToDecimal(event.path("quantity_micros"));
            Map<String, Object> trade = new LinkedHashMap<>();
            trade.put("time_ms", eventTsMs);
            trade.put("symbol", ticker);
            trade.put("trade_id", event.path("trade_id").asText(""));
            trade.put("yes_price", price);
            trade.put("no_price", microsToDecimal(event.path("no_price_micros")));
            trade.put("quantity", quantity);
            trade.put("taker_side", event.path("taker_side").asText(""));
            trade.put("event_id", event.path("event_id").asText(""));
            trades.add(trade);
            if (eventTsMs > 0L && price != null) {
                prices.add(new PricePoint(eventTsMs, price, quantity == null ? 0.0 : quantity));
            }
        }

        private void recordOpenInterest(JsonNode event, long eventTsMs) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time_ms", eventTsMs);
            point.put("symbol", ticker);
            point.put("open_interest", quantityToDecimal(event.path("open_interest_micros")));
            point.put("dollar_open_interest", event.path("dollar_open_interest").isNumber()
                ? event.path("dollar_open_interest").asLong()
                : null);
            openInterest.add(point);
        }

        private Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ticker", ticker);
            result.put("symbol", ticker);
            result.put("name", ticker);
            result.put("description", ticker);
            result.put("exchange", "KALSHI");
            result.put("type", "prediction_market");
            result.put("market_id", marketId);
            result.put("price_scale", "probability_dollars");
            result.put("minmov", 1);
            result.put("pricescale", 10000);
            result.put("supported_resolutions", List.of("1S", "1", "5", "60", "1D"));
            result.put("first_event_ts_ms", firstEventTsMs == 0L ? null : firstEventTsMs);
            result.put("last_event_ts_ms", lastEventTsMs == 0L ? null : lastEventTsMs);
            result.put("event_count", eventCount.get());
            return result;
        }

        private List<Map<String, Object>> bars(long bucketMs, Long fromMs, Long toMs, int limit) {
            List<PricePoint> filtered = prices.stream()
                .filter(point -> within(point.timeMs, fromMs, toMs))
                .sorted(Comparator.comparingLong(point -> point.timeMs))
                .toList();
            Map<Long, MutableBar> barsByBucket = new LinkedHashMap<>();
            for (PricePoint point : filtered) {
                long bucketStart = (point.timeMs / bucketMs) * bucketMs;
                barsByBucket.computeIfAbsent(bucketStart, MutableBar::new).add(point);
            }
            return barsByBucket.values().stream()
                .map(MutableBar::toMap)
                .skip(Math.max(0, barsByBucket.size() - Math.max(limit, 1)))
                .toList();
        }

        private Map<String, Object> quote() {
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("symbol", ticker);
            quote.put("price", latestTicker == null ? null : microsToDecimal(latestTicker.path("price_micros")));
            quote.put("bid", latestTicker == null ? null : microsToDecimal(latestTicker.path("yes_bid_micros")));
            quote.put("ask", latestTicker == null ? null : microsToDecimal(latestTicker.path("yes_ask_micros")));
            quote.put("bid_size", latestTicker == null ? null : quantityToDecimal(latestTicker.path("yes_bid_quantity_micros")));
            quote.put("ask_size", latestTicker == null ? null : quantityToDecimal(latestTicker.path("yes_ask_quantity_micros")));
            quote.put("volume", latestTicker == null ? null : quantityToDecimal(latestTicker.path("volume_micros")));
            quote.put("event_ts_ms", latestTicker == null ? lastEventTsMs : eventTsMs(latestTicker));
            if (latestTopOfBook != null) {
                quote.put("top_bid", microsToDecimal(latestTopOfBook.path("bid_price_micros")));
                quote.put("top_ask", microsToDecimal(latestTopOfBook.path("ask_price_micros")));
                quote.put("top_bid_size", quantityToDecimal(latestTopOfBook.path("bid_quantity_micros")));
                quote.put("top_ask_size", quantityToDecimal(latestTopOfBook.path("ask_quantity_micros")));
                quote.put("crossed", latestTopOfBook.path("crossed").asBoolean(false));
            }
            return quote;
        }

        private Map<String, Object> depth() {
            Map<String, Object> depth = new LinkedHashMap<>();
            depth.put("quote", quote());
            depth.put("latest_event_type", latestDepth == null ? null : latestDepth.path("event_type").asText());
            depth.put("latest_depth_event", latestDepth);
            return depth;
        }

        private List<Map<String, Object>> trades(Long fromMs, Long toMs, int limit) {
            List<Map<String, Object>> filtered = trades.stream()
                .filter(trade -> within(((Number) trade.getOrDefault("time_ms", 0L)).longValue(), fromMs, toMs))
                .toList();
            return filtered.stream()
                .skip(Math.max(0, filtered.size() - Math.max(limit, 1)))
                .toList();
        }

        private List<Map<String, Object>> openInterest(Long fromMs, Long toMs, int limit) {
            List<Map<String, Object>> filtered = openInterest.stream()
                .filter(point -> within(((Number) point.getOrDefault("time_ms", 0L)).longValue(), fromMs, toMs))
                .toList();
            return filtered.stream()
                .skip(Math.max(0, filtered.size() - Math.max(limit, 1)))
                .toList();
        }
    }

    private record PricePoint(long timeMs, double price, double volume) {
    }

    private static final class MutableBar {
        private final long timeMs;
        private double open;
        private double high;
        private double low;
        private double close;
        private double volume;
        private boolean initialized;

        private MutableBar(long timeMs) {
            this.timeMs = timeMs;
        }

        private void add(PricePoint point) {
            if (!initialized) {
                open = point.price;
                high = point.price;
                low = point.price;
                initialized = true;
            }
            high = Math.max(high, point.price);
            low = Math.min(low, point.price);
            close = point.price;
            volume += point.volume;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("time_ms", timeMs);
            bar.put("time", Instant.ofEpochMilli(timeMs).toString());
            bar.put("open", open);
            bar.put("high", high);
            bar.put("low", low);
            bar.put("close", close);
            bar.put("volume", volume);
            return bar;
        }
    }
}
