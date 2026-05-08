package edu.illinois.group8.feature;

import edu.illinois.group8.canonical.EventType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class TradeTapeFeatureModule implements FeatureModule {
    public static final String FEATURE_NAME = "feature.trade_tape";

    @Override
    public String name() {
        return FEATURE_NAME;
    }

    @Override
    public Set<String> inputStreams() {
        return Set.of(EventType.MARKET_TRADE.streamName());
    }

    @Override
    public void onEvent(CanonicalEnvelope envelope, FeatureOutputCollector collector) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("trade_id", FeatureJson.text(envelope.event(), "trade_id"));
        putIfPresent(values, "yes_price_micros", FeatureJson.optionalLong(envelope.event(), "yes_price_micros"));
        putIfPresent(values, "no_price_micros", FeatureJson.optionalLong(envelope.event(), "no_price_micros"));
        putIfPresent(values, "quantity_micros", FeatureJson.optionalLong(envelope.event(), "quantity_micros"));
        values.put("taker_side", FeatureJson.text(envelope.event(), "taker_side"));
        collector.emit(new FeatureOutput(
            name(),
            "feature.trade_tape",
            envelope.marketTicker(),
            envelope.eventTsMs(),
            envelope.eventId(),
            values
        ));
    }

    private void putIfPresent(Map<String, Object> values, String field, Long value) {
        if (value != null) {
            values.put(field, value);
        }
    }
}
