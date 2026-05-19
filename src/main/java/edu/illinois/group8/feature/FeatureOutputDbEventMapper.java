package edu.illinois.group8.feature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.storage.db.FeatureOutputDbEvent;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class FeatureOutputDbEventMapper {
    public static final int FEATURE_VERSION = 1;

    private static final String FEATURE_EVENT_ID_PREFIX = "feature_";
    private static final int FEATURE_EVENT_ID_HASH_CHARS = 24;
    private static final String NULL_SENTINEL = "\u0001";

    private final ObjectMapper mapper;

    public FeatureOutputDbEventMapper() {
        this(new JsonCanonicalSerializer().mapper().copy()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true));
    }

    FeatureOutputDbEventMapper(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public FeatureOutputDbEvent toDbEvent(FeatureOutput output) {
        Objects.requireNonNull(output, "output");

        String valuesJson = valuesJson(output);
        String sourceEventId = normalizeBlank(output.sourceEventId());
        String marketTicker = normalizeBlank(output.marketTicker());
        String featureEventId = featureEventId(output, sourceEventId, marketTicker, valuesJson);
        return new FeatureOutputDbEvent(
            featureEventId,
            sourceEventId,
            output.featureName(),
            FEATURE_VERSION,
            marketTicker,
            output.eventTsMs(),
            valuesJson
        );
    }

    String valuesJson(FeatureOutput output) {
        try {
            return mapper.writeValueAsString(sortedValue(output.values()));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Failed to serialize feature output values for " + output.featureName(),
                e
            );
        }
    }

    private static String featureEventId(
        FeatureOutput output,
        String sourceEventId,
        String marketTicker,
        String valuesJson
    ) {
        List<String> identity = new ArrayList<>();
        identity.add(output.featureName());
        identity.add(Integer.toString(FEATURE_VERSION));
        if (sourceEventId != null) {
            identity.add(sourceEventId);
            identity.add(marketTicker);
        } else {
            identity.add(marketTicker);
            identity.add(output.eventTsMs() == null ? null : Long.toString(output.eventTsMs()));
            identity.add(valuesJson);
        }
        return FEATURE_EVENT_ID_PREFIX + sha256(identity).substring(0, FEATURE_EVENT_ID_HASH_CHARS);
    }

    private static String sha256(List<String> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (part == null) {
                    digest.update(NULL_SENTINEL.getBytes(StandardCharsets.UTF_8));
                } else {
                    digest.update(part.getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Object sortedValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> {
                if (key != null && child != null) {
                    sorted.put(String.valueOf(key), sortedValue(child));
                }
            });
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sortedChildren = new ArrayList<>();
            for (Object child : iterable) {
                if (child != null) {
                    sortedChildren.add(sortedValue(child));
                }
            }
            return sortedChildren;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> sortedChildren = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object child = Array.get(value, i);
                if (child != null) {
                    sortedChildren.add(sortedValue(child));
                }
            }
            return sortedChildren;
        }
        return value;
    }

    private static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
