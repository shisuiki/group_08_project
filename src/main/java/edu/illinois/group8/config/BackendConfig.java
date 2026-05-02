package edu.illinois.group8.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record BackendConfig(
    String profile,
    String kalshiBaseUrl,
    String kalshiKeyId,
    String kalshiKeyPath,
    List<String> marketTickers,
    String marketSeriesTicker,
    List<String> websocketChannels,
    List<String> clusterAddresses,
    String nodeId,
    String hostIp,
    String baseDir,
    int clusterPortBase,
    String aeronChannel,
    Path journalRoot,
    String databaseUrl,
    String databaseUser,
    String databasePassword
) {
    public static final String PROFILE_LOCAL = "local";
    public static final String PROFILE_DOCKER = "docker";
    public static final String PROFILE_REPLAY = "replay";
    public static final String PROFILE_PRODUCTION = "production";

    public static BackendConfig fromEnvironment() {
        return from(Map.copyOf(System.getenv()), System.getProperties());
    }

    public static BackendConfig from(Map<String, String> env) {
        return from(env, System.getProperties());
    }

    private static BackendConfig from(Map<String, String> env, java.util.Properties properties) {
        String profile = value(env, properties, "BACKEND_PROFILE", PROFILE_LOCAL);
        String baseDir = value(env, properties, "BASE_DIR", "/app");
        return new BackendConfig(
            profile,
            value(env, properties, "KALSHI_BASE_URL", "https://api.elections.kalshi.com"),
            value(env, properties, "KALSHI_KEY_ID", ""),
            value(env, properties, "KALSHI_KEY_PATH", ""),
            csv(value(env, properties, "KALSHI_MARKET_TICKERS", "")),
            value(env, properties, "KALSHI_MARKET_SERIES_TICKER", ""),
            csv(value(env, properties, "KALSHI_WS_CHANNELS", "orderbook_delta,trade,ticker,market_lifecycle_v2")),
            csv(value(env, properties, "CLUSTER_ADDRESSES", "127.0.0.1")),
            value(env, properties, "NODE_ID", ""),
            value(env, properties, "IP", "127.0.0.1"),
            baseDir,
            intValue(env, properties, "CLUSTER_PORT_BASE", 9000),
            value(env, properties, "AERON_CHANNEL", "aeron:udp?endpoint=0.0.0.0:40456"),
            Path.of(value(env, properties, "BACKEND_JOURNAL_ROOT", baseDir + "/journal")),
            value(env, properties, "BACKEND_DATABASE_URL", ""),
            value(env, properties, "BACKEND_DATABASE_USER", value(env, properties, "DB_USER", "")),
            value(env, properties, "BACKEND_DATABASE_PASSWORD", value(env, properties, "DB_PASSWORD", ""))
        );
    }

    public void validateForLiveIngestion() {
        StringBuilder errors = new StringBuilder();
        if (isBlank(kalshiBaseUrl)) {
            errors.append("KALSHI_BASE_URL is required. ");
        }
        if (isBlank(kalshiKeyId)) {
            errors.append("KALSHI_KEY_ID is required for live ingestion. ");
        }
        if (isBlank(kalshiKeyPath)) {
            errors.append("KALSHI_KEY_PATH is required for live ingestion. ");
        }
        if (marketTickers.isEmpty() && isBlank(marketSeriesTicker)) {
            errors.append("Set KALSHI_MARKET_TICKERS or KALSHI_MARKET_SERIES_TICKER. ");
        }
        if (clusterAddresses.isEmpty()) {
            errors.append("CLUSTER_ADDRESSES must contain at least one host. ");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(errors.toString().trim());
        }
    }

    public void validateForClusterNode() {
        StringBuilder errors = new StringBuilder();
        if (clusterAddresses.isEmpty()) {
            errors.append("CLUSTER_ADDRESSES must contain at least one host. ");
        }
        if (isBlank(nodeId)) {
            errors.append("NODE_ID is required for cluster nodes. ");
        }
        if (isBlank(baseDir)) {
            errors.append("BASE_DIR is required. ");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(errors.toString().trim());
        }
    }

    public boolean isReplayProfile() {
        return PROFILE_REPLAY.equalsIgnoreCase(profile);
    }

    public boolean hasKalshiCredentials() {
        return !isBlank(kalshiKeyId) && !isBlank(kalshiKeyPath);
    }

    public Optional<String> databaseUrlOptional() {
        return Optional.ofNullable(databaseUrl).filter(v -> !v.isBlank());
    }

    private static String value(
        Map<String, String> env,
        java.util.Properties properties,
        String key,
        String defaultValue
    ) {
        String propertyValue = properties.getProperty(key);
        if (!isBlank(propertyValue)) {
            return propertyValue;
        }
        return Optional.ofNullable(env.get(key)).filter(v -> !v.isBlank()).orElse(defaultValue);
    }

    private static int intValue(
        Map<String, String> env,
        java.util.Properties properties,
        String key,
        int defaultValue
    ) {
        String value = value(env, properties, key, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " must be an integer: " + value, e);
        }
    }

    private static List<String> csv(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public BackendConfig {
        profile = Objects.requireNonNullElse(profile, PROFILE_LOCAL);
        kalshiBaseUrl = Objects.requireNonNullElse(kalshiBaseUrl, "https://api.elections.kalshi.com");
        kalshiKeyId = Objects.requireNonNullElse(kalshiKeyId, "");
        kalshiKeyPath = Objects.requireNonNullElse(kalshiKeyPath, "");
        marketTickers = List.copyOf(Objects.requireNonNullElse(marketTickers, List.of()));
        marketSeriesTicker = Objects.requireNonNullElse(marketSeriesTicker, "");
        websocketChannels = List.copyOf(Objects.requireNonNullElse(websocketChannels, List.of()));
        clusterAddresses = List.copyOf(Objects.requireNonNullElse(clusterAddresses, List.of()));
        nodeId = Objects.requireNonNullElse(nodeId, "");
        hostIp = Objects.requireNonNullElse(hostIp, "127.0.0.1");
        baseDir = Objects.requireNonNullElse(baseDir, "/app");
        aeronChannel = Objects.requireNonNullElse(aeronChannel, "aeron:udp?endpoint=0.0.0.0:40456");
        journalRoot = Objects.requireNonNullElse(journalRoot, Path.of(baseDir, "journal"));
        databaseUrl = Objects.requireNonNullElse(databaseUrl, "");
        databaseUser = Objects.requireNonNullElse(databaseUser, "");
        databasePassword = Objects.requireNonNullElse(databasePassword, "");
    }
}
