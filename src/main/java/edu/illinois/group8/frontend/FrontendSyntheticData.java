package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.storage.db.MarketMetadata;

import java.util.Locale;

final class FrontendSyntheticData {
    static final String SOURCE_KIND_UNKNOWN = "unknown";
    static final String SOURCE_KIND_SMOKE = "smoke";
    static final String SOURCE_KIND_DEMO = "demo";
    static final String SOURCE_KIND_LIVE = "live";

    private static final String SMOKE_SOURCE_EVENT_PREFIX = "live-product-smoke-";
    private static final String SMOKE_MARKET_PREFIX = "LIVE-PRODUCT-SMOKE-";
    private static final String SMOKE_TICKER = "LIVE-PRODUCT-SMOKE";
    private static final String SMOKE_RUN_ID_MARKER = "\"smoke_run_id\"";
    private static final String SMOKE_SOURCE_MARKER = "\"live_product_smoke\"";
    private static final String DEMO_SOURCE_EVENT_PREFIX = "demo-db-primary-";
    private static final String DEMO_MARKET_PREFIX = "DEMO-DBPRIMARY-";
    private static final String DEMO_TICKER = "DEMO-DBPRIMARY";
    private static final String DEMO_SOURCE_MARKER = "\"demo_seed\"";

    private FrontendSyntheticData() {
    }

    static String sourceKind(FeatureOutput output) {
        if (output == null) {
            return SOURCE_KIND_UNKNOWN;
        }
        if (isSmoke(output)) {
            return SOURCE_KIND_SMOKE;
        }
        return isDemo(output) ? SOURCE_KIND_DEMO : SOURCE_KIND_LIVE;
    }

    static String sourceKind(MarketMetadata metadata) {
        if (metadata == null) {
            return SOURCE_KIND_UNKNOWN;
        }
        if (isSmoke(metadata)) {
            return SOURCE_KIND_SMOKE;
        }
        return isDemo(metadata) ? SOURCE_KIND_DEMO : SOURCE_KIND_LIVE;
    }

    static boolean isSmoke(FeatureOutput output) {
        return output != null
            && (isSmokeSourceEventId(output.sourceEventId()) || isSmokeMarketTicker(output.marketTicker()));
    }

    static boolean isSmoke(MarketMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        return isSmokeMarketTicker(metadata.marketTicker())
            || SMOKE_TICKER.equalsIgnoreCase(trim(metadata.eventTicker()))
            || SMOKE_TICKER.equalsIgnoreCase(trim(metadata.seriesTicker()))
            || containsSmokeMarker(metadata.marketPayload())
            || containsSmokeMarker(metadata.rulesPayload());
    }

    static boolean isDemo(FeatureOutput output) {
        return output != null
            && (isDemoSourceEventId(output.sourceEventId()) || isDemoMarketTicker(output.marketTicker()));
    }

    static boolean isDemo(MarketMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        return isDemoMarketTicker(metadata.marketTicker())
            || DEMO_TICKER.equalsIgnoreCase(trim(metadata.eventTicker()))
            || DEMO_TICKER.equalsIgnoreCase(trim(metadata.seriesTicker()))
            || containsDemoMarker(metadata.marketPayload())
            || containsDemoMarker(metadata.rulesPayload());
    }

    static boolean isLive(FeatureOutput output) {
        return SOURCE_KIND_LIVE.equals(sourceKind(output));
    }

    static boolean isSmokeMarketTicker(String marketTicker) {
        String trimmed = trim(marketTicker);
        return trimmed != null && trimmed.toUpperCase(Locale.ROOT).startsWith(SMOKE_MARKET_PREFIX);
    }

    static boolean isSmokeSourceEventId(String sourceEventId) {
        String trimmed = trim(sourceEventId);
        return trimmed != null && trimmed.startsWith(SMOKE_SOURCE_EVENT_PREFIX);
    }

    static boolean isSynthetic(String sourceKind) {
        return SOURCE_KIND_SMOKE.equals(sourceKind) || SOURCE_KIND_DEMO.equals(sourceKind);
    }

    private static boolean isDemoMarketTicker(String marketTicker) {
        String trimmed = trim(marketTicker);
        return trimmed != null && trimmed.toUpperCase(Locale.ROOT).startsWith(DEMO_MARKET_PREFIX);
    }

    private static boolean isDemoSourceEventId(String sourceEventId) {
        String trimmed = trim(sourceEventId);
        return trimmed != null && trimmed.startsWith(DEMO_SOURCE_EVENT_PREFIX);
    }

    private static boolean containsSmokeMarker(String payload) {
        return payload != null
            && (payload.contains(SMOKE_RUN_ID_MARKER) || payload.contains(SMOKE_SOURCE_MARKER));
    }

    private static boolean containsDemoMarker(String payload) {
        return payload != null && payload.contains(DEMO_SOURCE_MARKER);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
