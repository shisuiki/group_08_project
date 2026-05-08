package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.JsonNode;

final class FeatureJson {
    private FeatureJson() {
    }

    static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }
}
