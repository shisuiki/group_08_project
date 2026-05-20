package edu.illinois.group8.semantic;

import java.util.regex.Pattern;

final class SemanticMetadataRedactor {
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{32,}");

    private SemanticMetadataRedactor() {
    }

    static String redact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String redacted = BEARER.matcher(value).replaceAll("Bearer [redacted]");
        redacted = redacted.replaceAll("(?i)(api[_-]?key|authorization|password|token)=([^\\s&]+)", "$1=[redacted]");
        return LONG_TOKEN.matcher(redacted).replaceAll("[redacted]");
    }

    static String configuredValue(boolean configured) {
        return configured ? "[configured]" : "";
    }
}
