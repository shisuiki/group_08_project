package edu.illinois.group8.semantic;

import java.time.Duration;

public final class OpenRouterException extends RuntimeException {
    private final int statusCode;
    private final boolean rateLimited;
    private final Duration retryAfter;

    public OpenRouterException(int statusCode, String message) {
        this(statusCode, message, null);
    }

    public OpenRouterException(int statusCode, String message, Duration retryAfter) {
        super(SemanticMetadataRedactor.redact(message));
        this.statusCode = statusCode;
        this.rateLimited = statusCode == 429;
        this.retryAfter = retryAfter;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean rateLimited() {
        return rateLimited;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean retryable() {
        return statusCode == 0
            || statusCode == 408
            || statusCode == 429
            || statusCode == 502
            || statusCode == 503
            || statusCode >= 500;
    }

    public boolean terminalConfigOrBilling() {
        return statusCode == 400 || statusCode == 401 || statusCode == 402 || statusCode == 403;
    }

    public boolean unavailableForFallback() {
        return retryable() && !terminalConfigOrBilling();
    }
}
