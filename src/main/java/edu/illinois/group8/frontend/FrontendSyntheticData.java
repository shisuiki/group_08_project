package edu.illinois.group8.frontend;

import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.storage.db.MarketMetadata;

import java.util.Locale;

final class FrontendSyntheticData {
    static final String SOURCE_KIND_UNKNOWN = "unknown";
    static final String SOURCE_KIND_SMOKE = "smoke";
    static final String SOURCE_KIND_LIVE = "live";

    private static final String SMOKE_SOURCE_EVENT_PREFIX = "live-product-smoke-";
    private static final String SMOKE_MARKET_PREFIX = "LIVE-PRODUCT-SMOKE-";
    private static final String SMOKE_TICKER = "LIVE-PRODUCT-SMOKE";
    private static final String SMOKE_RUN_ID_MARKER = "\"smoke_run_id\"";
    private static final String SMOKE_SOURCE_MARKER = "\"live_product_smoke\"";

    private FrontendSyntheticData() {
    }

    static String sourceKind(FeatureOutput output) {
        if (output == null) {
            return SOURCE_KIND_UNKNOWN;
        }
        return isSmoke(output) ? SOURCE_KIND_SMOKE : SOURCE_KIND_LIVE;
    }

    static String sourceKind(MarketMetadata metadata) {
        if (metadata == null) {
            return SOURCE_KIND_UNKNOWN;
        }
        return isSmoke(metadata) ? SOURCE_KIND_SMOKE : SOURCE_KIND_LIVE;
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

    static boolean isSmokeMarketTicker(String marketTicker) {
        String trimmed = trim(marketTicker);
        return trimmed != null && trimmed.toUpperCase(Locale.ROOT).startsWith(SMOKE_MARKET_PREFIX);
    }

    static boolean isSmokeSourceEventId(String sourceEventId) {
        String trimmed = trim(sourceEventId);
        return trimmed != null && trimmed.startsWith(SMOKE_SOURCE_EVENT_PREFIX);
    }

    static boolean isSynthetic(String sourceKind) {
        return SOURCE_KIND_SMOKE.equals(sourceKind);
    }

    private static boolean containsSmokeMarker(String payload) {
        return payload != null
            && (payload.contains(SMOKE_RUN_ID_MARKER) || payload.contains(SMOKE_SOURCE_MARKER));
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
