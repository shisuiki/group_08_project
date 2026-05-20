package edu.illinois.group8.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HttpOpenRouterClient implements OpenRouterClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI completionsUri;
    private final String apiKey;
    private final Duration timeout;
    private final String referer;
    private final String appTitle;

    public HttpOpenRouterClient(SemanticMetadataConfig config) {
        this(
            HttpClient.newHttpClient(),
            new JsonCanonicalSerializer().mapper().copy(),
            URI.create(config.openRouterBaseUrl().replaceAll("/+$", "") + "/chat/completions"),
            config.openRouterApiKey(),
            config.requestTimeout(),
            config.httpReferer(),
            config.appTitle()
        );
    }

    HttpOpenRouterClient(
        HttpClient httpClient,
        ObjectMapper mapper,
        URI completionsUri,
        String apiKey,
        Duration timeout,
        String referer,
        String appTitle
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.completionsUri = Objects.requireNonNull(completionsUri, "completionsUri");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.referer = referer == null ? "" : referer.trim();
        this.appTitle = appTitle == null ? "" : appTitle.trim();
    }

    @Override
    public OpenRouterCompletion complete(String model, List<OpenRouterMessage> messages, int maxTokens) {
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenRouter API key is not configured");
        }
        try {
            String body = mapper.writeValueAsString(requestBody(model, messages, maxTokens));
            HttpRequest.Builder builder = HttpRequest.newBuilder(completionsUri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!referer.isBlank()) {
                builder.header("HTTP-Referer", referer);
            }
            if (!appTitle.isBlank()) {
                builder.header("X-Title", appTitle);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenRouterException(
                    response.statusCode(),
                    "OpenRouter status " + response.statusCode() + ": " + response.body(),
                    retryAfter(response)
                );
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode choice = root.path("choices").isArray() && root.path("choices").size() > 0
                ? root.path("choices").get(0)
                : null;
            String content = choice == null ? "" : choice.path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new OpenRouterException(200, "OpenRouter response missing message content");
            }
            String actualModel = root.path("model").asText(model);
            String usage = root.path("usage").isMissingNode() || root.path("usage").isNull()
                ? null
                : mapper.writeValueAsString(root.path("usage"));
            return new OpenRouterCompletion(actualModel, content, response.body(), usage);
        } catch (IOException e) {
            throw new OpenRouterException(
                0,
                "OpenRouter request failed: " + SemanticMetadataRedactor.redact(e.getMessage())
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenRouter request interrupted", e);
        }
    }

    private static Map<String, Object> requestBody(
        String model,
        List<OpenRouterMessage> messages,
        int maxTokens
    ) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must be non-blank");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.trim());
        body.put("messages", messages.stream()
            .map(message -> Map.of("role", message.role(), "content", message.content()))
            .toList());
        body.put("temperature", 0);
        body.put("max_tokens", maxTokens);
        body.put("response_format", Map.of("type", "json_object"));
        return body;
    }

    private static Duration retryAfter(HttpResponse<String> response) {
        return response.headers()
            .firstValue("Retry-After")
            .map(HttpOpenRouterClient::parseRetryAfter)
            .orElse(null);
    }

    private static Duration parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds <= 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
