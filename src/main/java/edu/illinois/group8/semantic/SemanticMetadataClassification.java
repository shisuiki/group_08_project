package edu.illinois.group8.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

public record SemanticMetadataClassification(
    String sector,
    String subsector,
    String eventType,
    String region,
    String timeHorizon,
    String liquidityBucket,
    String riskBucket,
    String tags,
    BigDecimal confidence,
    String rationale
) {
    static SemanticMetadataClassification parse(String content, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(content);
            if (!root.isObject()) {
                throw new IllegalArgumentException("semantic response must be a JSON object");
            }
            require(root, "sector");
            requireOne(root, "subsector", "theme");
            require(root, "event_type");
            require(root, "region");
            require(root, "time_horizon");
            require(root, "liquidity_bucket");
            require(root, "risk_bucket");
            require(root, "confidence");
            require(root, "rationale");
            JsonNode tagsNode = root.path("tags");
            if (!tagsNode.isArray()) {
                throw new IllegalArgumentException("semantic response tags must be an array");
            }
            BigDecimal confidence = null;
            if (root.hasNonNull("confidence")) {
                if (!root.path("confidence").isNumber()) {
                    throw new IllegalArgumentException("semantic response confidence must be numeric or null");
                }
                confidence = root.path("confidence").decimalValue();
                if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                    throw new IllegalArgumentException("semantic response confidence out of range");
                }
            }
            return new SemanticMetadataClassification(
                textOrNull(root, "sector"),
                textOrNull(root, "subsector", "theme"),
                textOrNull(root, "event_type"),
                textOrNull(root, "region"),
                textOrNull(root, "time_horizon"),
                textOrNull(root, "liquidity_bucket"),
                textOrNull(root, "risk_bucket"),
                mapper.writeValueAsString(tagsNode),
                confidence,
                textOrNull(root, "rationale")
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse semantic metadata JSON", e);
        }
    }

    private static String textOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual()) {
                String text = value.asText().trim();
                return text.isBlank() ? null : text;
            }
        }
        return null;
    }

    private static void require(JsonNode node, String field) {
        if (!node.has(field)) {
            throw new IllegalArgumentException("semantic response missing required field " + field);
        }
    }

    private static void requireOne(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field)) {
                return;
            }
        }
        throw new IllegalArgumentException("semantic response missing required field " + fields[0]);
    }
}
