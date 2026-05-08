package edu.illinois.group8.integration;

import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.time.TimestampSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record IntegrationGatewayConfig(
    String host,
    int port,
    Path journalRoot,
    String aeronChannel,
    boolean aeronLiveEnabled,
    boolean seedJournalEnabled,
    boolean storageTailEnabled,
    long storagePollIntervalMs,
    List<StreamContract> streams,
    int maxIndexedEvents,
    String authToken,
    TimestampSource timestampSource
) {
    public static IntegrationGatewayConfig fromEnvironment() {
        Map<String, String> env = System.getenv();
        String baseDir = value(env, "BASE_DIR", "/app");
        List<StreamContract> streams = resolveStreams(value(
            env,
            "FRONTEND_ADAPTER_STREAMS",
            value(env, "GATEWAY_STREAMS",
            value(env, "STREAM_TAP_STREAMS",
                "canonical.trade,canonical.ticker,canonical.open_interest,canonical.orderbook.snapshot,canonical.orderbook.delta,derived.top_of_book,system.sequence_gaps")
            )
        ));
        return new IntegrationGatewayConfig(
            value(env, "FRONTEND_ADAPTER_HOST", value(env, "GATEWAY_HOST", "0.0.0.0")),
            intValue(env, "FRONTEND_ADAPTER_PORT", intValue(env, "GATEWAY_PORT", 8090)),
            Path.of(value(env, "BACKEND_JOURNAL_ROOT", baseDir + "/journal")),
            value(env, "FRONTEND_ADAPTER_AERON_CHANNEL",
                value(env, "GATEWAY_AERON_CHANNEL",
                value(env, "STREAM_TAP_CHANNEL",
                    value(env, "AERON_EXTERNAL_CHANNEL", "aeron:udp?endpoint=224.0.1.1:40456")))),
            booleanValue(env, "FRONTEND_ADAPTER_ENABLE_STREAM", booleanValue(env, "GATEWAY_ENABLE_AERON_LIVE", false)),
            booleanValue(env, "FRONTEND_ADAPTER_SEED_JOURNAL", true),
            booleanValue(env, "FRONTEND_ADAPTER_ENABLE_STORAGE_TAIL", true),
            longValue(env, "FRONTEND_ADAPTER_STORAGE_POLL_INTERVAL_MS", 1000L),
            streams,
            intValue(env, "FRONTEND_ADAPTER_MAX_INDEXED_EVENTS", intValue(env, "GATEWAY_MAX_INDEXED_EVENTS", 200_000)),
            value(env, "FRONTEND_ADAPTER_AUTH_TOKEN", value(env, "GATEWAY_AUTH_TOKEN", "")),
            TimestampSource.fromEnvironment()
        );
    }

    private static List<StreamContract> resolveStreams(String raw) {
        List<StreamContract> streams = new ArrayList<>();
        for (String name : csv(raw)) {
            Optional<StreamContract> contract = StreamRegistry.byName(name);
            if (contract.isEmpty()) {
                throw new IllegalArgumentException("Unknown frontend adapter stream name: " + name);
            }
            streams.add(contract.get());
        }
        if (streams.isEmpty()) {
            throw new IllegalArgumentException("FRONTEND_ADAPTER_STREAMS must include at least one canonical stream.");
        }
        return List.copyOf(streams);
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private static String value(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intValue(Map<String, String> env, String key, int defaultValue) {
        String raw = value(env, key, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer: " + raw, e);
        }
    }

    private static long longValue(Map<String, String> env, String key, long defaultValue) {
        String raw = value(env, key, Long.toString(defaultValue));
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a long: " + raw, e);
        }
    }

    private static boolean booleanValue(Map<String, String> env, String key, boolean defaultValue) {
        String raw = value(env, key, Boolean.toString(defaultValue));
        return Boolean.parseBoolean(raw);
    }
}
