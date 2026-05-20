package edu.illinois.group8.semantic;

public record OpenRouterCompletion(
    String model,
    String content,
    String rawResponse,
    String usage
) {
    public OpenRouterCompletion {
        model = value(model);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must be non-blank");
        }
        rawResponse = rawResponse == null || rawResponse.isBlank() ? "{}" : rawResponse;
        usage = usage == null || usage.isBlank() ? null : usage;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
