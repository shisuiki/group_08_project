package edu.illinois.group8.frontend;

import java.util.LinkedHashMap;
import java.util.Map;

public record FrontendReleaseInfo(
    String sha,
    String image,
    String profile,
    String runId,
    String runAttempt
) {
    public static FrontendReleaseInfo fromEnvironment() {
        return from(System.getenv());
    }

    public static FrontendReleaseInfo from(Map<String, String> env) {
        return new FrontendReleaseInfo(
            first(env, "KALSHI_RELEASE_SHA", "GITHUB_SHA"),
            first(env, "KALSHI_APP_IMAGE"),
            first(env, "KALSHI_DEPLOY_PROFILE", "DEPLOY_PROFILE"),
            first(env, "KALSHI_GITHUB_RUN_ID", "GITHUB_RUN_ID"),
            first(env, "KALSHI_GITHUB_RUN_ATTEMPT", "GITHUB_RUN_ATTEMPT")
        );
    }

    public static FrontendReleaseInfo empty() {
        return new FrontendReleaseInfo(null, null, null, null, null);
    }

    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sha", sha);
        body.put("image", image);
        body.put("profile", profile);
        body.put("run_id", runId);
        body.put("run_attempt", runAttempt);
        return body;
    }

    private static String first(Map<String, String> env, String... keys) {
        if (env == null) {
            return null;
        }
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
