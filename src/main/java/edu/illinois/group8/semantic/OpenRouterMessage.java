package edu.illinois.group8.semantic;

public record OpenRouterMessage(String role, String content) {
    public OpenRouterMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must be non-blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must be non-blank");
        }
        role = role.trim();
    }
}
