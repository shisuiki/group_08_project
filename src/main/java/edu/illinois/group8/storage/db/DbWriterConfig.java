package edu.illinois.group8.storage.db;

import java.util.Map;
import java.util.Objects;

public record DbWriterConfig(
    boolean enabled,
    String databaseUrl,
    String databaseUser,
    String databasePassword,
    String rawSource,
    String rawCaptureId,
    int queueCapacity,
    int batchSize
) {
    public static final String ENABLED_ENV = "DB_WRITER_ENABLED";
    public static final String DATABASE_URL_ENV = "DB_WRITER_DATABASE_URL";
    public static final String DATABASE_USER_ENV = "DB_WRITER_DATABASE_USER";
    public static final String DATABASE_PASSWORD_ENV = "DB_WRITER_DATABASE_PASSWORD";
    public static final String QUEUE_CAPACITY_ENV = "DB_WRITER_QUEUE_CAPACITY";
    public static final String BATCH_SIZE_ENV = "DB_WRITER_BATCH_SIZE";
    public static final String RAW_SOURCE_ENV = "DB_WRITER_RAW_SOURCE";
    public static final String RAW_CAPTURE_ID_ENV = "DB_WRITER_RAW_CAPTURE_ID";
    public static final String DEFAULT_RAW_SOURCE = "kalshi.websocket";
    public static final String DEFAULT_RAW_CAPTURE_ID = "live";
    public static final int DEFAULT_QUEUE_CAPACITY = 250_000;
    public static final int DEFAULT_BATCH_SIZE = 500;

    public DbWriterConfig {
        databaseUrl = normalize(databaseUrl);
        databaseUser = normalize(databaseUser);
        databasePassword = normalize(databasePassword);
        rawSource = normalizeOrDefault(rawSource, DEFAULT_RAW_SOURCE);
        rawCaptureId = normalizeOrDefault(rawCaptureId, DEFAULT_RAW_CAPTURE_ID);
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive.");
        }
        if (enabled && databaseUrl.isBlank()) {
            throw new IllegalArgumentException("databaseUrl must be set when DB writer is enabled.");
        }
    }

    public static DbWriterConfig fromEnvironment() {
        return from(System.getenv());
    }

    public static DbWriterConfig from(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        return new DbWriterConfig(
            parseEnabled(env.get(ENABLED_ENV), env.get(DATABASE_URL_ENV)),
            env.get(DATABASE_URL_ENV),
            env.get(DATABASE_USER_ENV),
            env.get(DATABASE_PASSWORD_ENV),
            env.get(RAW_SOURCE_ENV),
            env.get(RAW_CAPTURE_ID_ENV),
            parseInt(env.get(QUEUE_CAPACITY_ENV), DEFAULT_QUEUE_CAPACITY, QUEUE_CAPACITY_ENV),
            parseInt(env.get(BATCH_SIZE_ENV), DEFAULT_BATCH_SIZE, BATCH_SIZE_ENV)
        );
    }

    private static boolean parseEnabled(String value, String databaseUrl) {
        if (value == null || value.isBlank()) {
            return !normalize(databaseUrl).isBlank();
        }
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        throw new IllegalArgumentException(ENABLED_ENV + " must be true or false.");
    }

    private static int parseInt(String value, int defaultValue, String envName) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(envName + " must be an integer.", e);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private static String normalizeOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
