package edu.illinois.group8.cluster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.illinois.group8.book.OrderBookRecoveryCheckpoint;
import edu.illinois.group8.esb.DataProcessorRecoveryState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ESBClusterSnapshotCodec {
    private static final int VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS = Set.of(
        "version",
        "source_watermarks",
        "order_book_recovery_checkpoints"
    );
    private static final Set<String> SOURCE_WATERMARK_FIELDS = Set.of("subscription_id", "sequence");
    private static final Set<String> ORDER_BOOK_CHECKPOINT_FIELDS = Set.of("market_ticker", "last_sequence");

    private ESBClusterSnapshotCodec() {
    }

    public static byte[] encode(DataProcessorRecoveryState state) {
        if (state == null) {
            throw new IllegalArgumentException("Data processor recovery state must not be null.");
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", VERSION);

        ArrayNode sourceWatermarks = root.putArray("source_watermarks");
        new TreeMap<>(state.sourceWatermarks()).forEach((subscriptionId, sequence) -> {
            ObjectNode watermark = sourceWatermarks.addObject();
            watermark.put("subscription_id", subscriptionId);
            watermark.put("sequence", sequence);
        });

        ArrayNode checkpoints = root.putArray("order_book_recovery_checkpoints");
        state.orderBookRecoveryCheckpoints().stream()
            .sorted((left, right) -> left.marketTicker().compareTo(right.marketTicker()))
            .forEach(checkpoint -> {
                ObjectNode node = checkpoints.addObject();
                node.put("market_ticker", checkpoint.marketTicker());
                if (checkpoint.lastSequence() == null) {
                    node.putNull("last_sequence");
                } else {
                    node.put("last_sequence", checkpoint.lastSequence());
                }
            });

        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to encode ESB cluster snapshot.", e);
        }
    }

    public static DataProcessorRecoveryState decode(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("ESB cluster snapshot bytes must not be null.");
        }
        try {
            JsonNode root = MAPPER.readTree(bytes);
            requireObject(root, "ESB cluster snapshot");
            requireExactFields(root, ROOT_FIELDS, "ESB cluster snapshot");
            int version = requiredInt(root, "version");
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported ESB cluster snapshot version: " + version);
            }
            return new DataProcessorRecoveryState(
                sourceWatermarks(root.required("source_watermarks")),
                orderBookRecoveryCheckpoints(root.required("order_book_recovery_checkpoints"))
            );
        } catch (IOException | IllegalArgumentException e) {
            if (e instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("Malformed ESB cluster snapshot JSON.", e);
        }
    }

    private static Map<Long, Long> sourceWatermarks(JsonNode node) {
        requireArray(node, "source_watermarks");
        Map<Long, Long> watermarks = new LinkedHashMap<>();
        for (JsonNode watermark : node) {
            requireObject(watermark, "source watermark");
            requireExactFields(watermark, SOURCE_WATERMARK_FIELDS, "source watermark");
            long subscriptionId = requiredLong(watermark, "subscription_id");
            long sequence = requiredLong(watermark, "sequence");
            if (watermarks.put(subscriptionId, sequence) != null) {
                throw new IllegalArgumentException("Duplicate source sequence watermark: " + subscriptionId);
            }
        }
        return watermarks;
    }

    private static List<OrderBookRecoveryCheckpoint> orderBookRecoveryCheckpoints(JsonNode node) {
        requireArray(node, "order_book_recovery_checkpoints");
        ArrayList<OrderBookRecoveryCheckpoint> checkpoints = new ArrayList<>();
        for (JsonNode checkpoint : node) {
            requireObject(checkpoint, "order book recovery checkpoint");
            requireExactFields(checkpoint, ORDER_BOOK_CHECKPOINT_FIELDS, "order book recovery checkpoint");
            checkpoints.add(new OrderBookRecoveryCheckpoint(
                requiredText(checkpoint, "market_ticker"),
                optionalLong(checkpoint, "last_sequence")
            ));
        }
        return checkpoints;
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = requiredField(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Expected integer field: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = requiredField(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Expected long field: " + field);
        }
        return value.longValue();
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = requiredField(node, field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Expected nullable long field: " + field);
        }
        return value.longValue();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = requiredField(node, field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Expected text field: " + field);
        }
        return value.textValue();
    }

    private static JsonNode requiredField(JsonNode node, String field) {
        if (!node.has(field)) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return node.get(field);
    }

    private static void requireObject(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Expected JSON object for " + name + ".");
        }
    }

    private static void requireArray(JsonNode node, String name) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("Expected JSON array for " + name + ".");
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> allowedFields, String name) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("Unexpected field in " + name + ": " + field);
            }
        }
        for (String field : allowedFields) {
            if (!node.has(field)) {
                throw new IllegalArgumentException("Missing required field: " + field);
            }
        }
    }
}
