package edu.illinois.group8.feature;

import edu.illinois.group8.canonical.EventType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class TickerSnapshotFeatureModule implements FeatureModule {
    public static final String FEATURE_NAME = "feature.ticker_snapshot";

    @Override
    public String name() {
        return FEATURE_NAME;
    }

    @Override
    public Set<String> inputStreams() {
        return Set.of(EventType.TICKER_UPDATE.streamName());
    }

    @Override
    public void onEvent(CanonicalEnvelope envelope, FeatureOutputCollector collector) {
        Map<String, Object> values = new LinkedHashMap<>();
        putIfPresent(values, "price_micros", FeatureJson.optionalLong(envelope.event(), "price_micros"));
        Long bid = FeatureJson.optionalLong(envelope.event(), "yes_bid_micros");
        Long ask = FeatureJson.optionalLong(envelope.event(), "yes_ask_micros");
        putIfPresent(values, "yes_bid_micros", bid);
        putIfPresent(values, "yes_ask_micros", ask);
        putIfPresent(values, "yes_bid_quantity_micros", FeatureJson.optionalLong(envelope.event(), "yes_bid_quantity_micros"));
        putIfPresent(values, "yes_ask_quantity_micros", FeatureJson.optionalLong(envelope.event(), "yes_ask_quantity_micros"));
        putIfPresent(values, "last_trade_quantity_micros", FeatureJson.optionalLong(envelope.event(), "last_trade_quantity_micros"));
        putIfPresent(values, "volume_micros", FeatureJson.optionalLong(envelope.event(), "volume_micros"));
        putIfPresent(values, "dollar_volume", FeatureJson.optionalLong(envelope.event(), "dollar_volume"));
        if (bid != null && ask != null) {
            values.put("spread_micros", ask - bid);
        }
        if (!values.isEmpty()) {
            collector.emit(new FeatureOutput(
                name(),
                "feature.ticker_snapshot",
                envelope.marketTicker(),
                envelope.eventTsMs(),
                envelope.eventId(),
                values
            ));
        }
    }

    private void putIfPresent(Map<String, Object> values, String field, Long value) {
        if (value != null) {
            values.put(field, value);
        }
    }
}
