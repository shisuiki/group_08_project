package edu.illinois.group8.config;

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
    String marketSelectionMode,
    String marketStatus,
    String marketMveFilter,
    int marketDiscoveryLimit,
    int marketDiscoveryMaxMarkets,
    List<String> websocketChannels,
    List<String> websocketGlobalChannels,
    List<String> websocketFilteredChannels,
    int orderbookSubscriptionChunkSize,
    int orderbookMarketsPerConnection,
    int subscriptionDelayMs,
    int subscriptionAckTimeoutMs,
    boolean websocketReconnectEnabled,
    int websocketReconnectInitialBackoffMs,
    int websocketReconnectMaxBackoffMs,
    int websocketReconnectMaxAttempts,
    boolean orderBookRecoveryGapConsumerEnabled,
    int orderBookRecoveryGapConsumerFragmentLimit,
    int orderBookRecoveryGapConsumerIdleSleepMs,
    boolean sourceSequenceMonitorEnabled,
    boolean orderBookDerivedEnabled,
    List<String> clusterAddresses,
    String nodeId,
    String hostIp,
    String baseDir,
    int clusterPortBase,
    String aeronChannel,
    String metricsHost,
    int metricsPort
) {
    public static final String PROFILE_LOCAL = "local";
    public static final String PROFILE_DOCKER = "docker";
    public static final String PROFILE_PRODUCTION = "production";
    public static final String PROFILE_RECORDING_CAPTURE = "recording-capture";

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
            value(env, properties, "KALSHI_MARKET_SELECTION_MODE", "configured"),
            value(env, properties, "KALSHI_MARKET_STATUS", "open"),
            value(env, properties, "KALSHI_MARKET_MVE_FILTER", ""),
            intValue(env, properties, "KALSHI_MARKET_DISCOVERY_LIMIT", 1000),
            intValue(env, properties, "KALSHI_MARKET_DISCOVERY_MAX_MARKETS", 0),
            csv(value(env, properties, "KALSHI_WS_CHANNELS", "orderbook_delta,trade,ticker,market_lifecycle_v2")),
            csv(value(env, properties, "KALSHI_WS_GLOBAL_CHANNELS", "")),
            csv(value(env, properties, "KALSHI_WS_FILTERED_CHANNELS", "")),
            intValue(env, properties, "KALSHI_ORDERBOOK_SUBSCRIPTION_CHUNK_SIZE", 100),
            intValue(env, properties, "KALSHI_ORDERBOOK_MARKETS_PER_CONNECTION", 10000),
            intValue(env, properties, "KALSHI_WS_SUBSCRIPTION_DELAY_MS", 250),
            intValue(env, properties, "KALSHI_WS_ACK_TIMEOUT_MS", 30000),
            booleanValue(env, properties, "BACKEND_WS_RECONNECT_ENABLED", true),
            intValue(env, properties, "BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS", 1000),
            intValue(env, properties, "BACKEND_WS_RECONNECT_MAX_BACKOFF_MS", 30000),
            intValue(env, properties, "BACKEND_WS_RECONNECT_MAX_ATTEMPTS", 0),
            booleanValue(env, properties, "BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_ENABLED", false),
            intValue(env, properties, "BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_FRAGMENT_LIMIT", 64),
            intValue(env, properties, "BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_IDLE_SLEEP_MS", 1),
            booleanValue(env, properties, "BACKEND_SOURCE_SEQUENCE_MONITOR_ENABLED", false),
            booleanValue(env, properties, "BACKEND_ORDERBOOK_DERIVED_ENABLED", true),
            csv(value(env, properties, "CLUSTER_ADDRESSES", "127.0.0.1")),
            value(env, properties, "NODE_ID", ""),
            value(env, properties, "IP", "127.0.0.1"),
            baseDir,
            intValue(env, properties, "CLUSTER_PORT_BASE", 9000),
            aeronChannel(env, properties),
            value(env, properties, "BACKEND_METRICS_HOST", "0.0.0.0"),
            intValue(env, properties, "BACKEND_METRICS_PORT", 8091)
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
        if (!openMarketSelectionEnabled() && marketTickers.isEmpty() && isBlank(marketSeriesTicker)) {
            errors.append("Set KALSHI_MARKET_TICKERS or KALSHI_MARKET_SERIES_TICKER. ");
        }
        if (marketDiscoveryLimit < 1 || marketDiscoveryLimit > 1000) {
            errors.append("KALSHI_MARKET_DISCOVERY_LIMIT must be between 1 and 1000. ");
        }
        String normalizedMveFilter = marketMveFilter.trim().toLowerCase();
        if (!isBlank(marketMveFilter) && !"only".equals(normalizedMveFilter) && !"exclude".equals(normalizedMveFilter)) {
            errors.append("KALSHI_MARKET_MVE_FILTER must be blank, only, or exclude. ");
        }
        if (marketDiscoveryMaxMarkets < 0) {
            errors.append("KALSHI_MARKET_DISCOVERY_MAX_MARKETS must be zero or positive. ");
        }
        if (orderbookSubscriptionChunkSize < 1) {
            errors.append("KALSHI_ORDERBOOK_SUBSCRIPTION_CHUNK_SIZE must be positive. ");
        }
        if (orderbookMarketsPerConnection < orderbookSubscriptionChunkSize) {
            errors.append("KALSHI_ORDERBOOK_MARKETS_PER_CONNECTION must be at least KALSHI_ORDERBOOK_SUBSCRIPTION_CHUNK_SIZE. ");
        }
        if (subscriptionDelayMs < 0) {
            errors.append("KALSHI_WS_SUBSCRIPTION_DELAY_MS must be zero or positive. ");
        }
        if (subscriptionAckTimeoutMs < 1) {
            errors.append("KALSHI_WS_ACK_TIMEOUT_MS must be positive. ");
        }
        if (websocketReconnectInitialBackoffMs < 0) {
            errors.append("BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS must be zero or positive. ");
        }
        if (websocketReconnectMaxBackoffMs < websocketReconnectInitialBackoffMs) {
            errors.append("BACKEND_WS_RECONNECT_MAX_BACKOFF_MS must be at least BACKEND_WS_RECONNECT_INITIAL_BACKOFF_MS. ");
        }
        if (websocketReconnectMaxAttempts < 0) {
            errors.append("BACKEND_WS_RECONNECT_MAX_ATTEMPTS must be zero or positive. ");
        }
        if (orderBookRecoveryGapConsumerFragmentLimit < 1) {
            errors.append("BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_FRAGMENT_LIMIT must be positive. ");
        }
        if (orderBookRecoveryGapConsumerIdleSleepMs < 0) {
            errors.append("BACKEND_ORDERBOOK_RECOVERY_GAP_CONSUMER_IDLE_SLEEP_MS must be zero or positive. ");
        }
        if (metricsPort < 0) {
            errors.append("BACKEND_METRICS_PORT must be zero or positive. ");
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

    public boolean hasKalshiCredentials() {
        return !isBlank(kalshiKeyId) && !isBlank(kalshiKeyPath);
    }

    public boolean openMarketSelectionEnabled() {
        String normalized = marketSelectionMode.trim().toLowerCase();
        return "open".equals(normalized)
            || "open_markets".equals(normalized)
            || "all_open".equals(normalized)
            || "all-open".equals(normalized);
    }

    public boolean recordingCaptureProfileEnabled() {
        return PROFILE_RECORDING_CAPTURE.equalsIgnoreCase(profile.trim());
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

    private static boolean booleanValue(
        Map<String, String> env,
        java.util.Properties properties,
        String key,
        boolean defaultValue
    ) {
        String value = value(env, properties, key, Boolean.toString(defaultValue));
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static String aeronChannel(Map<String, String> env, java.util.Properties properties) {
        return value(
            env,
            properties,
            "AERON_EXTERNAL_CHANNEL",
            value(env, properties, "AERON_CHANNEL", "aeron:udp?endpoint=224.0.1.1:40456")
        );
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
        marketSelectionMode = Objects.requireNonNullElse(marketSelectionMode, "configured");
        marketStatus = Objects.requireNonNullElse(marketStatus, "open");
        marketMveFilter = Objects.requireNonNullElse(marketMveFilter, "").trim().toLowerCase();
        websocketChannels = List.copyOf(Objects.requireNonNullElse(websocketChannels, List.of()));
        websocketGlobalChannels = List.copyOf(Objects.requireNonNullElse(websocketGlobalChannels, List.of()));
        websocketFilteredChannels = List.copyOf(Objects.requireNonNullElse(websocketFilteredChannels, List.of()));
        clusterAddresses = List.copyOf(Objects.requireNonNullElse(clusterAddresses, List.of()));
        nodeId = Objects.requireNonNullElse(nodeId, "");
        hostIp = Objects.requireNonNullElse(hostIp, "127.0.0.1");
        baseDir = Objects.requireNonNullElse(baseDir, "/app");
        aeronChannel = Objects.requireNonNullElse(aeronChannel, "aeron:udp?endpoint=224.0.1.1:40456");
        metricsHost = Objects.requireNonNullElse(metricsHost, "0.0.0.0");
    }
}
