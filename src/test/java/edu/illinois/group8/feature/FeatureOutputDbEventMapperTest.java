package edu.illinois.group8.feature;

import edu.illinois.group8.storage.db.FeatureOutputDbEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureOutputDbEventMapperTest {
    private final FeatureOutputDbEventMapper mapper = new FeatureOutputDbEventMapper();

    @Test
    void serializesValuesWithSortedKeys() {
        FeatureOutput output = output("feature.bbo", "source-1", "M1", 100L, orderedValues("z", 3, "a", 1));

        FeatureOutputDbEvent event = mapper.toDbEvent(output);

        assertEquals("{\"a\":1,\"z\":3}", event.values());
    }

    @Test
    void deterministicIdsIgnoreValueInsertionOrder() {
        FeatureOutput first = output("feature.bbo", "source-1", "M1", 100L, orderedValues("b", 2, "a", 1));
        FeatureOutput second = output("feature.bbo", "source-1", "M1", 200L, orderedValues("a", 9, "b", 8));

        FeatureOutputDbEvent firstEvent = mapper.toDbEvent(first);
        FeatureOutputDbEvent secondEvent = mapper.toDbEvent(second);

        assertEquals(firstEvent.featureEventId(), secondEvent.featureEventId());
        assertTrue(firstEvent.featureEventId().startsWith("feature_"));
        assertEquals(32, firstEvent.featureEventId().length());
    }

    @Test
    void sourceEventIdentityChangesForDifferentSourceOrMarket() {
        FeatureOutput base = output("feature.bbo", "source-1", "M1", 100L, Map.of("midpoint", 1));
        FeatureOutput differentSource = output("feature.bbo", "source-2", "M1", 100L, Map.of("midpoint", 1));
        FeatureOutput differentMarket = output("feature.bbo", "source-1", "M2", 100L, Map.of("midpoint", 1));

        String baseId = mapper.toDbEvent(base).featureEventId();

        assertNotEquals(baseId, mapper.toDbEvent(differentSource).featureEventId());
        assertNotEquals(baseId, mapper.toDbEvent(differentMarket).featureEventId());
    }

    @Test
    void nullSourceIdentityUsesEventTimestampAndValues() {
        FeatureOutput first = output("feature.bbo", null, "M1", 100L, orderedValues("b", 2, "a", 1));
        FeatureOutput reordered = output("feature.bbo", " ", "M1", 100L, orderedValues("a", 1, "b", 2));
        FeatureOutput differentTimestamp = output("feature.bbo", null, "M1", 101L, orderedValues("a", 1, "b", 2));
        FeatureOutput differentValues = output("feature.bbo", null, "M1", 100L, orderedValues("a", 1, "b", 3));

        String firstId = mapper.toDbEvent(first).featureEventId();

        assertEquals(firstId, mapper.toDbEvent(reordered).featureEventId());
        assertNotEquals(firstId, mapper.toDbEvent(differentTimestamp).featureEventId());
        assertNotEquals(firstId, mapper.toDbEvent(differentValues).featureEventId());
    }

    @Test
    void recursivelySortsNestedMapsAndLists() {
        Map<String, Object> nested = orderedValues(
            "outer_b", List.of(orderedValues("z", 2, "a", 1)),
            "outer_a", orderedValues("c", 3, "b", 2)
        );

        FeatureOutputDbEvent event = mapper.toDbEvent(output("feature.nested", "source-1", "M1", 1L, nested));

        assertEquals(
            "{\"outer_a\":{\"b\":2,\"c\":3},\"outer_b\":[{\"a\":1,\"z\":2}]}",
            event.values()
        );
    }

    @Test
    void validationRejectsMissingFeatureName() {
        assertThrows(
            IllegalArgumentException.class,
            () -> mapper.toDbEvent(output(" ", "source-1", "M1", 1L, Map.of("a", 1)))
        );
    }

    @Test
    void serializationFailureIncludesFeatureName() {
        FeatureOutput output = output("feature.bad", "source-1", "M1", 1L, Map.of("bad", new BadValue()));

        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> mapper.toDbEvent(output)
        );

        assertTrue(thrown.getMessage().contains("feature.bad"));
    }

    private static FeatureOutput output(
        String featureName,
        String sourceEventId,
        String marketTicker,
        Long eventTsMs,
        Map<String, Object> values
    ) {
        return new FeatureOutput(featureName, featureName, marketTicker, eventTsMs, sourceEventId, values);
    }

    private static Map<String, Object> orderedValues(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put((String) entries[i], entries[i + 1]);
        }
        return values;
    }

    private static final class BadValue {
        @SuppressWarnings("unused")
        public String getValue() {
            throw new IllegalStateException("cannot serialize");
        }
    }
}
