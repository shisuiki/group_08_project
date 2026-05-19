package edu.illinois.group8.frontend;

import edu.illinois.group8.storage.db.MarketMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class FrontendMarketMetadataCatalog {
    public enum LoadStatus {
        DISABLED, LOADED, UNAVAILABLE
    }

    private final String source;
    private final LoadStatus loadStatus;
    private final String error;
    private final Map<String, MarketMetadata> byTicker;

    private FrontendMarketMetadataCatalog(
        String source,
        LoadStatus loadStatus,
        String error,
        Map<String, MarketMetadata> byTicker
    ) {
        this.source = source == null || source.isBlank() ? "disabled" : source;
        this.loadStatus = loadStatus == null ? LoadStatus.DISABLED : loadStatus;
        this.error = error;
        this.byTicker = Map.copyOf(byTicker);
    }

    public static FrontendMarketMetadataCatalog disabled(String source) {
        return new FrontendMarketMetadataCatalog(source, LoadStatus.DISABLED, null, Map.of());
    }

    public static FrontendMarketMetadataCatalog unavailable(String source, String error) {
        return new FrontendMarketMetadataCatalog(source, LoadStatus.UNAVAILABLE, error, Map.of());
    }

    public static FrontendMarketMetadataCatalog loaded(String source, List<MarketMetadata> rows) {
        Map<String, MarketMetadata> byTicker = new LinkedHashMap<>();
        if (rows != null) {
            for (MarketMetadata row : rows) {
                if (row == null || row.marketTicker() == null || row.marketTicker().isBlank()) {
                    continue;
                }
                byTicker.put(row.marketTicker(), row);
            }
        }
        return new FrontendMarketMetadataCatalog(source, LoadStatus.LOADED, null, byTicker);
    }

    public String source() {
        return source;
    }

    public LoadStatus loadStatus() {
        return loadStatus;
    }

    public String error() {
        return error;
    }

    public int size() {
        return byTicker.size();
    }

    public Optional<MarketMetadata> find(String marketTicker) {
        if (marketTicker == null || marketTicker.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byTicker.get(marketTicker.trim()));
    }

    public List<MarketMetadata> search(String query, String status, int limit) {
        String normalizedQuery = normalize(query);
        String normalizedStatus = normalize(status);
        int boundedLimit = Math.max(1, limit);
        List<MarketMetadata> matches = new ArrayList<>();
        List<MarketMetadata> rows = byTicker.values().stream()
            .sorted(Comparator.comparing(MarketMetadata::marketTicker))
            .toList();
        for (MarketMetadata row : rows) {
            if (normalizedStatus != null && !normalizedStatus.equalsIgnoreCase(nullToEmpty(row.status()))) {
                continue;
            }
            if (normalizedQuery != null && !matchesQuery(row, normalizedQuery)) {
                continue;
            }
            matches.add(row);
            if (matches.size() >= boundedLimit) {
                break;
            }
        }
        return List.copyOf(matches);
    }

    private static boolean matchesQuery(MarketMetadata row, String query) {
        return contains(row.marketTicker(), query)
            || contains(row.eventTicker(), query)
            || contains(row.seriesTicker(), query)
            || contains(row.status(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
