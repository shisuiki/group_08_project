package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.utils.Cryptography;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class KalshiLiveCredentialPreflight {
    static final String DEFAULT_KALSHI_BASE_URL = "https://api.elections.kalshi.com";
    private static final String MARKETS_PATH = "/trade-api/v2/markets";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();

    KalshiLiveCredentialPreflight() {
        this(HttpClient.newHttpClient());
    }

    KalshiLiveCredentialPreflight(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    LiveCredentialCheckResult check(LiveCredentialCheckConfig config) {
        LiveCredentialCheckConfig normalized = Objects.requireNonNull(config, "config");
        if (!normalized.configured()) {
            return LiveCredentialCheckResult.failure(
                normalized.configured(),
                "credentials_missing",
                null,
                "Kalshi key id plus private key path or PEM is required"
            );
        }

        PrivateKey privateKey;
        try {
            privateKey = loadPrivateKey(normalized);
        } catch (Exception e) {
            return LiveCredentialCheckResult.failure(true, "auth_failed", null, "Kalshi private key could not be loaded");
        }

        String pathWithQuery = MARKETS_PATH + "?limit=1";
        int status;
        String responseBody;
        try {
            String timestamp = Long.toString(System.currentTimeMillis());
            String signature = Cryptography.signMessage(timestamp + "GET" + MARKETS_PATH, privateKey);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalized.baseUrl() + pathWithQuery))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .header("KALSHI-ACCESS-KEY", normalized.keyId())
                .header("KALSHI-ACCESS-SIGNATURE", signature)
                .header("KALSHI-ACCESS-TIMESTAMP", timestamp)
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            status = response.statusCode();
            responseBody = response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LiveCredentialCheckResult.failure(true, "network", null, "Kalshi credential check was interrupted");
        } catch (Exception e) {
            return LiveCredentialCheckResult.failure(true, "network", null, e.getMessage());
        }

        if (status == 401 || status == 403) {
            return LiveCredentialCheckResult.failure(true, "auth_failed", status, "Kalshi credential check was rejected");
        }
        if (status == 429) {
            return LiveCredentialCheckResult.failure(true, "rate_limited", status, "Kalshi credential check was rate limited");
        }
        if (status < 200 || status >= 300) {
            return LiveCredentialCheckResult.failure(true, "network", status, "Kalshi credential check returned HTTP " + status);
        }

        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode markets = root.path("markets");
            if (!markets.isArray()) {
                return LiveCredentialCheckResult.failure(true, "parse_error", status, "Kalshi markets response was missing markets array");
            }
            int marketCount = markets.size();
            String sampleTicker = "";
            if (marketCount > 0) {
                sampleTicker = firstText(markets.get(0), "ticker", "market_ticker");
            }
            return LiveCredentialCheckResult.success(status, marketCount, sampleTicker);
        } catch (Exception e) {
            return LiveCredentialCheckResult.failure(true, "parse_error", status, "Kalshi credential check response was not parseable");
        }
    }

    private static PrivateKey loadPrivateKey(LiveCredentialCheckConfig config) throws Exception {
        if (!config.keyPath().isBlank()) {
            return Cryptography.loadPrivateKey(config.keyPath());
        }
        return Cryptography.loadPrivateKeyFromPem(config.keyPem());
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText("").isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    @FunctionalInterface
    interface LiveCredentialChecker {
        LiveCredentialCheckResult check(LiveCredentialCheckConfig config);
    }

    record LiveCredentialCheckConfig(String baseUrl, String keyId, String keyPath, String keyPem) {
        LiveCredentialCheckConfig {
            baseUrl = normalize(baseUrl);
            keyId = normalize(keyId);
            keyPath = normalize(keyPath);
            keyPem = keyPem == null ? "" : keyPem.trim();
            if (baseUrl.isBlank()) {
                baseUrl = DEFAULT_KALSHI_BASE_URL;
            }
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }

        boolean configured() {
            return !keyId.isBlank() && (!keyPath.isBlank() || !keyPem.isBlank());
        }

        Map<String, Object> redactedBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", "live");
            body.put("base_url", OperatorRedactor.redact(baseUrl));
            body.put("key_id_configured", !keyId.isBlank());
            body.put("private_key_path_configured", !keyPath.isBlank());
            body.put("private_key_pem_configured", !keyPem.isBlank());
            body.put("configured", configured());
            return body;
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    record LiveCredentialCheckResult(
        boolean configured,
        boolean authOk,
        Integer httpStatus,
        int marketCount,
        String sampleTicker,
        String source,
        String failureCategory,
        String error
    ) {
        static LiveCredentialCheckResult success(int httpStatus, int marketCount, String sampleTicker) {
            return new LiveCredentialCheckResult(
                true,
                true,
                httpStatus,
                Math.max(0, marketCount),
                sampleTicker == null ? "" : sampleTicker,
                "live",
                "",
                ""
            );
        }

        static LiveCredentialCheckResult failure(
            boolean configured,
            String failureCategory,
            Integer httpStatus,
            String error
        ) {
            return new LiveCredentialCheckResult(
                configured,
                false,
                httpStatus,
                0,
                "",
                "live",
                failureCategory == null || failureCategory.isBlank() ? "network" : failureCategory,
                OperatorRedactor.redact(error)
            );
        }

        Map<String, Object> toBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", source);
            body.put("configured", configured);
            body.put("auth_ok", authOk);
            body.put("http_status", httpStatus);
            body.put("market_count", marketCount);
            body.put("sample_ticker", sampleTicker == null || sampleTicker.isBlank() ? null : sampleTicker);
            body.put("failure_category", failureCategory == null || failureCategory.isBlank() ? null : failureCategory);
            body.put("error", error == null || error.isBlank() ? null : error);
            return body;
        }
    }
}
