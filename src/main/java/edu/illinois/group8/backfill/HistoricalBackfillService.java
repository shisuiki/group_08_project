package edu.illinois.group8.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.parser.CanonicalParseResult;
import edu.illinois.group8.parser.KalshiRestParser;
import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.wrapper.RequestParameters;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class HistoricalBackfillService {
    private final HistoricalBackfillClient client;
    private final KalshiRestParser parser;
    private final CanonicalBackfillSink canonicalSink;
    private final RawRestBackfillSink rawRestSink;
    private final MarketMetadataBackfillSink marketMetadataSink;
    private final MarketMetadataMapper marketMetadataMapper;
    private final BackendMetrics metrics;
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();

    public HistoricalBackfillService(
        HistoricalBackfillClient client,
        KalshiRestParser parser,
        CanonicalBackfillSink canonicalSink,
        RawRestBackfillSink rawRestSink,
        BackendMetrics metrics
    ) {
        this(client, parser, canonicalSink, rawRestSink, null, metrics);
    }

    public HistoricalBackfillService(
        HistoricalBackfillClient client,
        KalshiRestParser parser,
        CanonicalBackfillSink canonicalSink,
        RawRestBackfillSink rawRestSink,
        MarketMetadataBackfillSink marketMetadataSink,
        BackendMetrics metrics
    ) {
        this.client = client;
        this.parser = parser;
        this.canonicalSink = canonicalSink;
        this.rawRestSink = rawRestSink;
        this.marketMetadataSink = marketMetadataSink;
        this.marketMetadataMapper = new MarketMetadataMapper();
        this.metrics = metrics;
    }

    public HistoricalBackfillSummary run(HistoricalBackfillConfig config) {
        config.validate();
        Counters counters = new Counters();
        LinkedHashSet<String> tickers = new LinkedHashSet<>(config.tickers());

        if (config.includeMarkets()) {
            for (String rawPayload : fetchMarketPages(config, counters)) {
                parseAndRecord("rest.markets", null, rawPayload, counters, config);
                tickers.addAll(extractTickers(rawPayload));
            }
        }

        List<String> selectedTickers = limitTickers(tickers, config.maxTickers());
        for (String ticker : selectedTickers) {
            if (config.includeTrades()) {
                fetchTradePages(config, ticker, counters).forEach(rawPayload -> {
                    parseAndRecord("rest.trades", ticker, rawPayload, counters, config);
                });
            }
            if (config.includeOrderbookSnapshots()) {
                String rawPayload = client.getMarketOrderbook(ticker, request(params -> params.addParam("depth", 0)));
                if (rawPayload != null && !rawPayload.isBlank()) {
                    counters.restResponsesFetched++;
                }
                recordResponse("rest.orderbook", ticker, rawPayload, counters, config, payload ->
                    parser.parseMarketOrderbookResponse(ticker, payload, counters.currentFetchTsNs));
            }
            if (config.includeCandlesticks()) {
                String rawPayload = client.getMarketCandlesticks(
                    ticker,
                    config.seriesTicker(),
                    config.startTs(),
                    config.endTs(),
                    config.periodInterval()
                );
                if (rawPayload != null && !rawPayload.isBlank()) {
                    counters.restResponsesFetched++;
                }
                recordResponse("rest.candlesticks", ticker, rawPayload, counters, config, payload ->
                    parser.parseCandlesticksResponse(payload, counters.currentFetchTsNs));
            }
        }

        counters.marketsDiscovered = selectedTickers.size();
        return new HistoricalBackfillSummary(
            counters.restResponsesFetched,
            counters.rawResponsesRecorded,
            counters.canonicalEventsParsed,
            counters.canonicalEventsRecorded,
            counters.marketsDiscovered,
            counters.failures
        );
    }

    private List<String> fetchMarketPages(HistoricalBackfillConfig config, Counters counters) {
        List<String> payloads = new ArrayList<>();
        String cursor = "";
        for (int page = 0; page < config.maxPages(); page++) {
            String cursorForRequest = cursor;
            RequestParameters params = request(p -> {
                p.addParam("limit", config.limit());
                p.addParam("status", config.marketStatus());
                if (!config.seriesTicker().isBlank()) {
                    p.addParam("series_ticker", config.seriesTicker());
                }
                if (!config.marketMveFilter().isBlank()) {
                    p.addParam("mve_filter", config.marketMveFilter());
                }
                if (cursorForRequest != null && !cursorForRequest.isBlank()) {
                    p.addParam("cursor", cursorForRequest);
                }
            });
            String rawPayload = client.getMarkets(params);
            if (rawPayload == null || rawPayload.isBlank()) {
                counters.failures++;
                break;
            }
            counters.restResponsesFetched++;
            payloads.add(rawPayload);
            cursor = cursor(rawPayload);
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return payloads;
    }

    private List<String> fetchTradePages(HistoricalBackfillConfig config, String ticker, Counters counters) {
        List<String> payloads = new ArrayList<>();
        String cursor = "";
        for (int page = 0; page < config.maxPages(); page++) {
            String cursorForRequest = cursor;
            RequestParameters params = request(p -> {
                p.addParam("limit", config.limit());
                p.addParam("ticker", ticker);
                if (config.startTs() != null) {
                    p.addParam("min_ts", config.startTs());
                }
                if (config.endTs() != null) {
                    p.addParam("max_ts", config.endTs());
                }
                if (cursorForRequest != null && !cursorForRequest.isBlank()) {
                    p.addParam("cursor", cursorForRequest);
                }
            });
            String rawPayload = client.getTrades(params);
            if (rawPayload == null || rawPayload.isBlank()) {
                counters.failures++;
                break;
            }
            counters.restResponsesFetched++;
            payloads.add(rawPayload);
            cursor = cursor(rawPayload);
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return payloads;
    }

    private CanonicalParseResult parseAndRecord(
        String endpoint,
        String ticker,
        String rawPayload,
        Counters counters,
        HistoricalBackfillConfig config
    ) {
        return recordResponse(endpoint, ticker, rawPayload, counters, config, payload -> switch (endpoint) {
            case "rest.markets" -> parser.parseMarketsResponse(payload, counters.currentFetchTsNs);
            case "rest.trades" -> parser.parseTradesResponse(payload, counters.currentFetchTsNs);
            default -> throw new IllegalArgumentException("Unsupported REST endpoint parser: " + endpoint);
        });
    }

    private CanonicalParseResult recordResponse(
        String endpoint,
        String ticker,
        String rawPayload,
        Counters counters,
        HistoricalBackfillConfig config,
        ParserCall parserCall
    ) {
        if (rawPayload == null || rawPayload.isBlank()) {
            counters.failures++;
            return new CanonicalParseResult(null, List.of());
        }
        long fetchTsNs = config.timestampSource().nowNanos();
        counters.currentFetchTsNs = fetchTsNs;
        Instant fetchWallTs = Instant.now();
        try {
            if (!config.dryRun() && rawRestSink != null) {
                rawRestSink.write(endpoint, ticker, rawPayload, fetchTsNs, fetchWallTs);
                counters.rawResponsesRecorded++;
            }
            List<MarketMetadata> marketMetadata = marketMetadata(endpoint, rawPayload);
            CanonicalParseResult result = parserCall.parse(rawPayload);
            var canonicalEvents = result.canonicalEvents();
            counters.canonicalEventsParsed += canonicalEvents.size();
            if (!config.dryRun() && !canonicalEvents.isEmpty()) {
                canonicalSink.writeBatch(canonicalEvents, fetchTsNs);
                counters.canonicalEventsRecorded += canonicalEvents.size();
            }
            if (!config.dryRun() && !marketMetadata.isEmpty()) {
                marketMetadataSink.writeBatch(marketMetadata);
            }
            for (var event : canonicalEvents) {
                metrics.increment("historical_backfill_canonical_events_total",
                    BackendMetrics.labels("endpoint", endpoint, "stream", event.streamName()));
            }
            return result;
        } catch (Exception e) {
            counters.failures++;
            throw new IllegalStateException("Historical backfill failed for " + endpoint + (ticker == null ? "" : " " + ticker), e);
        }
    }

    private List<MarketMetadata> marketMetadata(String endpoint, String rawPayload) {
        if (!"rest.markets".equals(endpoint) || marketMetadataSink == null) {
            return List.of();
        }
        return marketMetadataMapper.fromMarketsResponse(rawPayload);
    }

    private List<String> extractTickers(String rawPayload) {
        try {
            JsonNode markets = mapper.readTree(rawPayload).path("markets");
            if (!markets.isArray()) {
                return List.of();
            }
            List<String> tickers = new ArrayList<>();
            for (JsonNode market : markets) {
                String ticker = firstText(market, "ticker", "market_ticker");
                if (!ticker.isBlank()) {
                    tickers.add(ticker);
                }
            }
            return tickers;
        } catch (IOException e) {
            return List.of();
        }
    }

    private String cursor(String rawPayload) {
        try {
            return mapper.readTree(rawPayload).path("cursor").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = node.path(fieldName).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static List<String> limitTickers(LinkedHashSet<String> tickers, int maxTickers) {
        if (maxTickers == 0 || tickers.size() <= maxTickers) {
            return List.copyOf(tickers);
        }
        return tickers.stream().limit(maxTickers).toList();
    }

    private static RequestParameters request(ParamBuilder builder) {
        RequestParameters params = new RequestParameters();
        builder.accept(params);
        return params;
    }

    private interface ParamBuilder {
        void accept(RequestParameters params);
    }

    private interface ParserCall {
        CanonicalParseResult parse(String rawPayload);
    }

    private static final class Counters {
        private long restResponsesFetched;
        private long rawResponsesRecorded;
        private long canonicalEventsParsed;
        private long canonicalEventsRecorded;
        private long marketsDiscovered;
        private long failures;
        private long currentFetchTsNs;
    }
}
