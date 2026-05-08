package edu.illinois.group8.feature;

import edu.illinois.group8.canonical.EventType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class BestBidOfferFeatureModule implements FeatureModule {
    public static final String FEATURE_NAME = "feature.bbo";

    @Override
    public String name() {
        return FEATURE_NAME;
    }

    @Override
    public Set<String> inputStreams() {
        return Set.of(EventType.TOP_OF_BOOK_UPDATE.streamName());
    }

    @Override
    public void onEvent(CanonicalEnvelope envelope, FeatureOutputCollector collector) {
        Long bidPrice = FeatureJson.optionalLong(envelope.event(), "bid_price_micros");
        Long askPrice = FeatureJson.optionalLong(envelope.event(), "ask_price_micros");
        Long bidQuantity = FeatureJson.optionalLong(envelope.event(), "bid_quantity_micros");
        Long askQuantity = FeatureJson.optionalLong(envelope.event(), "ask_quantity_micros");
        if (bidPrice == null || askPrice == null) {
            return;
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("bid_price_micros", bidPrice);
        values.put("ask_price_micros", askPrice);
        values.put("bid_quantity_micros", bidQuantity);
        values.put("ask_quantity_micros", askQuantity);
        values.put("spread_micros", askPrice - bidPrice);
        values.put("midpoint_micros", (bidPrice + askPrice) / 2L);
        values.put("crossed", envelope.event().path("crossed").asBoolean(false));
        collector.emit(output(envelope, values));
    }

    private FeatureOutput output(CanonicalEnvelope envelope, Map<String, Object> values) {
        return new FeatureOutput(
            name(),
            "feature.bbo",
            envelope.marketTicker(),
            envelope.eventTsMs(),
            envelope.eventId(),
            values
        );
    }
}
