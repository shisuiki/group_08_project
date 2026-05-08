package edu.illinois.group8.feature;

import java.util.LinkedHashMap;
import java.util.Map;

public record FeatureOutput(
    String featureName,
    String streamName,
    String marketTicker,
    Long eventTsMs,
    String sourceEventId,
    Map<String, Object> values
) {
    public FeatureOutput {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        values = Map.copyOf(copy);
    }
}
