package edu.illinois.group8.storage.db;

import java.util.Locale;

public record MarketCapabilityReadRequest(
    String query,
    String status,
    String capabilityFilter,
    int limit,
    int offset,
    String taxonomyVersion,
    boolean includeSmoke,
    boolean includeIneligible
) {
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 5_000;
    public static final int DEFAULT_OFFSET = 0;
    public static final String DEFAULT_TAXONOMY_VERSION = "v1";

    public MarketCapabilityReadRequest {
        query = normalize(query);
        status = normalize(status);
        capabilityFilter = normalizeFilter(capabilityFilter);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        limit = Math.min(limit, MAX_LIMIT);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        taxonomyVersion = normalize(taxonomyVersion);
        if (taxonomyVersion == null) {
            taxonomyVersion = DEFAULT_TAXONOMY_VERSION;
        }
    }

    public static MarketCapabilityReadRequest defaultRequest() {
        return new MarketCapabilityReadRequest(
            null,
            null,
            "all",
            DEFAULT_LIMIT,
            DEFAULT_OFFSET,
            DEFAULT_TAXONOMY_VERSION,
            false,
            false
        );
    }

    public boolean filterAll() {
        return "all".equals(capabilityFilter);
    }

    private static String normalizeFilter(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return "all";
        }
        String filter = normalized.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (filter) {
            case "quote_available" -> "quote_available";
            case "quote_stale" -> "quote_stale";
            case "all", "chart_ready", "quote_only", "metadata_only", "semantic_tagged", "unclassified" -> filter;
            default -> throw new IllegalArgumentException(
                "capability filter must be all, chart_ready, quote_available, quote_only, quote_stale, "
                    + "metadata_only, semantic_tagged, or unclassified"
            );
        };
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
