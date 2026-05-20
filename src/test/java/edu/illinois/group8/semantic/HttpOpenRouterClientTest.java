package edu.illinois.group8.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpOpenRouterClientTest {
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper().copy();

    @Test
    void postsChatCompletionAndParsesContent() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (ServerHandle server = start(exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                {"model":"actual","choices":[{"message":{"content":"{\\"sector\\":\\"politics\\",\\"tags\\":[\\"election\\"],\\"confidence\\":0.9,\\"rationale\\":\\"clear\\"}"}}],"usage":{"total_tokens":12}}
                """);
        })) {
            HttpOpenRouterClient client = client(server.uri(), "sk-secret-token");

            OpenRouterCompletion completion = client.complete(
                "requested",
                List.of(new OpenRouterMessage("user", "classify")),
                50
            );

            JsonNode request = mapper.readTree(body.get());
            assertEquals("/chat/completions", requestPath.get());
            assertEquals("Bearer sk-secret-token", auth.get());
            assertEquals("requested", request.path("model").asText());
            assertEquals(50, request.path("max_tokens").asInt());
            assertEquals("json_object", request.path("response_format").path("type").asText());
            assertEquals("actual", completion.model());
            assertTrue(completion.content().contains("\"sector\":\"politics\""));
            assertEquals(12, mapper.readTree(completion.usage()).path("total_tokens").asInt());
        }
    }

    @Test
    void mapsRateLimitAndRedactsSecrets() throws Exception {
        try (ServerHandle server = start(exchange ->
            respond(exchange, 429, "{\"error\":\"Bearer sk-this-secret-should-not-leak-1234567890\"}", "5")
        )) {
            OpenRouterException thrown = assertThrows(
                OpenRouterException.class,
                () -> client(server.uri(), "sk-this-secret-should-not-leak-1234567890")
                    .complete("model", List.of(new OpenRouterMessage("user", "classify")), 50)
            );

            assertEquals(429, thrown.statusCode());
            assertTrue(thrown.rateLimited());
            assertEquals(Duration.ofSeconds(5), thrown.retryAfter());
            assertTrue(!thrown.getMessage().contains("sk-this-secret"));
            assertTrue(thrown.getMessage().contains("[redacted]"));
        }
    }

    @Test
    void malformedSuccessPayloadFailsWithoutSecretLeak() throws Exception {
        try (ServerHandle server = start(exchange -> respond(exchange, 200, "not-json"))) {
            OpenRouterException thrown = assertThrows(
                OpenRouterException.class,
                () -> client(server.uri(), "sk-secret-token")
                    .complete("model", List.of(new OpenRouterMessage("user", "classify")), 50)
            );

            assertEquals(0, thrown.statusCode());
            assertTrue(thrown.getMessage().contains("OpenRouter request failed"));
            assertTrue(!thrown.getMessage().contains("sk-secret-token"));
        }
    }

    private HttpOpenRouterClient client(URI baseUri, String apiKey) {
        return new HttpOpenRouterClient(
            HttpClient.newHttpClient(),
            mapper,
            baseUri.resolve("/chat/completions"),
            apiKey,
            Duration.ofSeconds(5),
            "https://example.test",
            "kalshi-test"
        );
    }

    private static ServerHandle start(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new ServerHandle(server, uri);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body, null);
    }

    private static void respond(HttpExchange exchange, int status, String body, String retryAfter) throws IOException {
        if (retryAfter != null) {
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record ServerHandle(HttpServer server, URI uri) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
