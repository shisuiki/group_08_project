package edu.illinois.group8.frontend;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class OperatorControlPlane {
    private static final Set<String> SUPPORTED_PROFILES = Set.of(
        "long-replay-demo",
        "live-product-local-db",
        "live-product"
    );

    private final FrontendAdapterConfig config;
    private final FrontendReleaseInfo releaseInfo;
    private final Map<String, String> env;

    OperatorControlPlane(FrontendAdapterConfig config, FrontendReleaseInfo releaseInfo) {
        this(config, releaseInfo, System.getenv());
    }

    OperatorControlPlane(
        FrontendAdapterConfig config,
        FrontendReleaseInfo releaseInfo,
        Map<String, String> env
    ) {
        this.config = config;
        this.releaseInfo = releaseInfo == null ? FrontendReleaseInfo.empty() : releaseInfo;
        this.env = env == null ? Map.of() : Map.copyOf(env);
    }

    Map<String, Object> configurationStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kalshi", kalshiStatus());
        body.put("db", dbStatus());
        body.put("s3", s3Status());
        body.put("basic_auth", basicAuthStatus());
        body.put("release", releaseInfo.toBody());
        return body;
    }

    Map<String, Object> controlStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", config.operatorControlEnabled());
        body.put("post_requires_enabled", true);
        body.put("post_requires_basic_auth", true);
        body.put("post_allowed", config.operatorControlEnabled() && config.basicAuthEnabled());
        body.put("basic_auth_enabled", config.basicAuthEnabled());
        return body;
    }

    Map<String, Object> plan(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("operator plan request must be a JSON object");
        }
        PlanInput input = PlanInput.from(request);
        boolean supportedProfile = SUPPORTED_PROFILES.contains(input.profile());
        boolean liveProfile = input.profile().startsWith("live-product");
        boolean externalLive = "live-product".equals(input.profile());
        boolean dbConfigured = input.dbUrlConfigured() && input.dbUserConfigured() && input.dbPasswordPresent();
        boolean localDbProfile = "live-product-local-db".equals(input.profile());
        boolean kalshiConfigured = input.kalshiKeyIdConfigured() && input.privateKeyConfigured();
        boolean basicAuthConfigured = input.basicAuthUserConfigured() && input.basicAuthPasswordPresent();
        boolean imageOrRefConfigured = input.imageConfigured() || input.refConfigured();

        List<Map<String, Object>> checklist = new ArrayList<>();
        checklist.add(check(
            "profile_supported",
            "Profile is supported",
            true,
            supportedProfile,
            supportedProfile ? input.profile() : "supported profiles: " + String.join(", ", SUPPORTED_PROFILES)
        ));
        checklist.add(check(
            "kalshi_credentials",
            "Kalshi credentials are present",
            liveProfile,
            !liveProfile || kalshiConfigured,
            liveProfile ? "key id plus private key path or PEM presence required" : "not required for replay demo"
        ));
        checklist.add(check(
            "database",
            "Database configuration is present",
            externalLive,
            !externalLive || dbConfigured,
            localDbProfile ? "compose local DB profile supplies DB defaults" : "DB URL, user, and password presence"
        ));
        checklist.add(check(
            "basic_auth",
            "Basic Auth protects product UI",
            liveProfile,
            !liveProfile || basicAuthConfigured,
            liveProfile ? "live product profiles fail closed without Basic Auth" : "optional for local replay demo"
        ));
        checklist.add(check(
            "image_or_ref",
            "Image or release ref is present",
            liveProfile,
            !liveProfile || imageOrRefConfigured,
            liveProfile ? "set image or ref before deploy" : "not required for local replay demo"
        ));
        checklist.add(check(
            "s3_capture",
            "S3 capture target is configured",
            false,
            input.s3BucketConfigured(),
            input.s3BucketConfigured() ? "recording capture can upload" : "optional; local capture/debug remains local"
        ));

        boolean canDeploy = supportedProfile
            && liveProfile
            && kalshiConfigured
            && (!externalLive || dbConfigured)
            && basicAuthConfigured
            && imageOrRefConfigured;
        boolean canReplay = supportedProfile && "long-replay-demo".equals(input.profile());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", canDeploy || canReplay ? "ready" : "blocked");
        body.put("profile", input.profile());
        body.put("can_deploy", canDeploy);
        body.put("can_replay", canReplay);
        body.put("checklist", checklist);
        body.put("redacted_env", redactedEnv(input));
        body.put("commands", commands(input, supportedProfile, canDeploy, canReplay));
        return body;
    }

    private Map<String, Object> kalshiStatus() {
        boolean keyId = configured(firstEnv("KALSHI_KEY_ID"));
        boolean keyPath = configured(firstEnv("KALSHI_KEY_PATH"));
        boolean privateKey = configured(firstEnv("KALSHI_PRIVATE_KEY"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key_id_configured", keyId);
        body.put("private_key_path_configured", keyPath);
        body.put("private_key_pem_configured", privateKey);
        return body;
    }

    private Map<String, Object> dbStatus() {
        String dbUrl = firstNonBlank(config.dbUrl(), firstEnv("FRONTEND_ADAPTER_DB_URL", "DB_WRITER_DATABASE_URL"));
        String dbUser = firstNonBlank(config.dbUser(), firstEnv("FRONTEND_ADAPTER_DB_USER", "DB_WRITER_DATABASE_USER"));
        String dbPassword = firstNonBlank(
            config.dbPassword(),
            firstEnv("FRONTEND_ADAPTER_DB_PASSWORD", "DB_WRITER_DATABASE_PASSWORD")
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url_configured", configured(dbUrl));
        body.put("url_redacted", OperatorRedactor.redact(dbUrl));
        body.put("user_configured", configured(dbUser));
        body.put("password_configured", configured(dbPassword));
        body.put("include_replay", config.dbIncludeReplayEvents());
        body.put("replay_id_configured", configured(config.dbReplayId()));
        return body;
    }

    private Map<String, Object> s3Status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bucket_configured", configured(firstEnv("S3_RECORDING_BUCKET")));
        body.put("region", firstEnv("AWS_REGION", "AWS_DEFAULT_REGION"));
        body.put("prefix", firstEnv("S3_RECORDING_PREFIX"));
        return body;
    }

    private Map<String, Object> basicAuthStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", config.basicAuthEnabled());
        body.put("user_configured", configured(config.basicAuthUser()));
        body.put("password_configured", configured(config.basicAuthPassword()));
        return body;
    }

    private Map<String, Object> redactedEnv(PlanInput input) {
        Map<String, Object> envPlan = new LinkedHashMap<>();
        envPlan.put("KALSHI_DEPLOY_PROFILE", input.profile());
        envPlan.put("KALSHI_KEY_ID", OperatorRedactor.configuredValue(input.kalshiKeyIdConfigured()));
        envPlan.put("KALSHI_KEY_PATH", input.privateKeyPath());
        envPlan.put("KALSHI_PRIVATE_KEY", OperatorRedactor.secretPresence(input.privateKeyPemPresent()));
        envPlan.put("DB_WRITER_DATABASE_URL", OperatorRedactor.redact(input.dbUrl()));
        envPlan.put("DB_WRITER_DATABASE_USER", OperatorRedactor.configuredValue(input.dbUserConfigured()));
        envPlan.put("DB_WRITER_DATABASE_PASSWORD", OperatorRedactor.secretPresence(input.dbPasswordPresent()));
        envPlan.put("FRONTEND_ADAPTER_BASIC_AUTH_USER",
            OperatorRedactor.configuredValue(input.basicAuthUserConfigured()));
        envPlan.put("FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD",
            OperatorRedactor.secretPresence(input.basicAuthPasswordPresent()));
        envPlan.put("S3_RECORDING_BUCKET", input.s3Bucket());
        envPlan.put("AWS_REGION", input.s3Region());
        envPlan.put("S3_RECORDING_PREFIX", input.s3Prefix());
        envPlan.put("KALSHI_APP_IMAGE", input.image());
        envPlan.put("KALSHI_RELEASE_SHA", input.ref());
        return envPlan;
    }

    private static List<String> commands(
        PlanInput input,
        boolean supportedProfile,
        boolean canDeploy,
        boolean canReplay
    ) {
        List<String> commands = new ArrayList<>();
        if (!supportedProfile) {
            commands.add("choose a supported profile before generating commands");
            return commands;
        }
        if (canReplay) {
            commands.add("PRODUCT_DEMO_SCENARIO=long-replay FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs "
                + "scripts/db-primary-product-smoke.sh");
            commands.add("scripts/frontend-product-browser-smoke.sh");
            return commands;
        }
        commands.add("docker compose --env-file .env --profile " + input.profile() + " config");
        if (canDeploy) {
            commands.add("docker compose --env-file .env --profile " + input.profile() + " up -d");
            commands.add("RUN_LIVE_PRODUCT_SMOKE=true DEPLOY_PROFILE=" + input.profile()
                + " scripts/live-product-smoke.sh");
        } else {
            commands.add("fix blocked checklist items before compose up");
        }
        return commands;
    }

    private static Map<String, Object> check(
        String id,
        String label,
        boolean required,
        boolean passed,
        String message
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("label", label);
        body.put("required", required);
        body.put("passed", passed);
        body.put("message", message);
        return body;
    }

    private String firstEnv(String... keys) {
        for (String key : keys) {
            String value = env.get(key);
            if (configured(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(String first, String second) {
        return configured(first) ? first.trim() : configured(second) ? second.trim() : "";
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank();
    }

    private record PlanInput(
        String profile,
        String kalshiKeyId,
        String privateKeyPath,
        boolean privateKeyPemPresent,
        String s3Bucket,
        String s3Region,
        String s3Prefix,
        String dbUrl,
        String dbUser,
        boolean dbPasswordPresent,
        String basicAuthUser,
        boolean basicAuthPasswordPresent,
        String image,
        String ref
    ) {
        private static PlanInput from(JsonNode root) {
            JsonNode kalshi = root.path("kalshi");
            JsonNode s3 = root.path("s3");
            JsonNode db = root.path("db");
            JsonNode basicAuth = root.path("basic_auth");
            JsonNode release = root.path("release");
            String privateKeyPem = firstNonBlank(
                text(kalshi, "private_key_pem", "KALSHI_PRIVATE_KEY", "pem"),
                text(root, "private_key_pem", "KALSHI_PRIVATE_KEY")
            );
            String dbPassword = firstNonBlank(
                text(db, "password", "DB_WRITER_DATABASE_PASSWORD"),
                text(root, "db_password", "DB_WRITER_DATABASE_PASSWORD")
            );
            String basicAuthPassword = firstNonBlank(
                text(basicAuth, "password", "FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD"),
                text(root, "basic_auth_password", "FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD")
            );
            return new PlanInput(
                lower(text(root, "profile", "deploy_profile", "KALSHI_DEPLOY_PROFILE", "live-product")),
                firstNonBlank(text(kalshi, "key_id", "KALSHI_KEY_ID"), text(root, "kalshi_key_id", "KALSHI_KEY_ID")),
                firstNonBlank(text(kalshi, "private_key_path", "key_path", "KALSHI_KEY_PATH"),
                    text(root, "private_key_path", "KALSHI_KEY_PATH")),
                bool(kalshi, "private_key_pem_present", "private_key_present")
                    || bool(root, "private_key_pem_present", "private_key_present")
                    || configured(privateKeyPem),
                firstNonBlank(text(s3, "bucket", "S3_RECORDING_BUCKET"), text(root, "s3_bucket")),
                firstNonBlank(text(s3, "region", "AWS_REGION"), text(root, "aws_region")),
                firstNonBlank(text(s3, "prefix", "S3_RECORDING_PREFIX"), text(root, "s3_prefix")),
                firstNonBlank(text(db, "url", "DB_WRITER_DATABASE_URL"), text(root, "db_url")),
                firstNonBlank(text(db, "user", "DB_WRITER_DATABASE_USER"), text(root, "db_user")),
                bool(db, "password_present", "db_password_present")
                    || bool(root, "db_password_present")
                    || configured(dbPassword),
                firstNonBlank(text(basicAuth, "user", "FRONTEND_ADAPTER_BASIC_AUTH_USER"),
                    text(root, "basic_auth_user", "FRONTEND_ADAPTER_BASIC_AUTH_USER")),
                bool(basicAuth, "password_present", "basic_auth_password_present")
                    || bool(root, "basic_auth_password_present")
                    || configured(basicAuthPassword),
                firstNonBlank(text(release, "image", "KALSHI_APP_IMAGE"), text(root, "image", "app_image")),
                firstNonBlank(text(release, "ref", "sha", "KALSHI_RELEASE_SHA"), text(root, "ref", "release_sha"))
            );
        }

        private boolean kalshiKeyIdConfigured() {
            return configured(kalshiKeyId);
        }

        private boolean privateKeyConfigured() {
            return configured(privateKeyPath) || privateKeyPemPresent;
        }

        private boolean s3BucketConfigured() {
            return configured(s3Bucket);
        }

        private boolean dbUrlConfigured() {
            return configured(dbUrl);
        }

        private boolean dbUserConfigured() {
            return configured(dbUser);
        }

        private boolean basicAuthUserConfigured() {
            return configured(basicAuthUser);
        }

        private boolean imageConfigured() {
            return configured(image);
        }

        private boolean refConfigured() {
            return configured(ref);
        }
    }

    private static String text(JsonNode node, String key1, String key2, String key3, String fallback) {
        String value = text(node, key1, key2, key3);
        return configured(value) ? value : fallback;
    }

    private static String text(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                String text = value.asText();
                if (configured(text)) {
                    return text.trim();
                }
            }
        }
        return "";
    }

    private static boolean bool(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isTextual()) {
                return switch (value.asText().trim().toLowerCase(Locale.ROOT)) {
                    case "1", "true", "yes", "y", "on" -> true;
                    default -> false;
                };
            }
        }
        return false;
    }

    private static String lower(String value) {
        return configured(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
