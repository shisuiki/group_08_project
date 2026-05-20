package edu.illinois.group8.frontend;

import java.util.regex.Pattern;

final class OperatorRedactor {
    private static final int MAX_OPERATOR_ERROR_LENGTH = 240;
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
        "-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
        "(?i)(authorization\\s*[:=]\\s*basic\\s+)[^\\s,;]+"
    );
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
        "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key)\\b\\s*([=:])\\s*[^\\s,;&]+"
    );
    private static final Pattern URI_USERINFO_PATTERN = Pattern.compile(
        "(?i)([a-z][a-z0-9+.-]*:(?://)?)([^\\s/@:]+):([^\\s/@]+)@"
    );
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z0-9_./+=-]{32,}\\b");

    private OperatorRedactor() {
    }

    static String redact(String message) {
        if (message == null) {
            return null;
        }
        String redacted = PRIVATE_KEY_PATTERN.matcher(message).replaceAll("[redacted-private-key]");
        redacted = AUTH_HEADER_PATTERN.matcher(redacted).replaceAll("$1[redacted]");
        redacted = URI_USERINFO_PATTERN.matcher(redacted).replaceAll("$1[redacted]@");
        redacted = PASSWORD_PATTERN.matcher(redacted).replaceAll("$1$2[redacted]");
        redacted = LONG_TOKEN_PATTERN.matcher(redacted).replaceAll("[redacted]");
        if (redacted.length() > MAX_OPERATOR_ERROR_LENGTH) {
            redacted = redacted.substring(0, MAX_OPERATOR_ERROR_LENGTH) + "...";
        }
        return redacted;
    }

    static String configuredValue(boolean configured) {
        return configured ? "<configured>" : "";
    }

    static String secretPresence(boolean configured) {
        return configured ? "<set via secret>" : "";
    }
}
