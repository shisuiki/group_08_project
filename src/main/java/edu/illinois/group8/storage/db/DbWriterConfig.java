package edu.illinois.group8.storage.db;

import java.util.Map;
import java.util.Objects;

public record DbWriterConfig(
    boolean enabled,
    String databaseUrl,
    String databaseUser,
    String databasePassword,
    int queueCapacity,
    int batchSize
) {
    public static final String ENABLED_ENV = "DB_WRITER_ENABLED";
    public static final String DATABASE_URL_ENV = "DB_WRITER_DATABASE_URL";
    public static final String DATABASE_USER_ENV = "DB_WRITER_DATABASE_USER";
    public static final String DATABASE_PASSWORD_ENV = "DB_WRITER_DATABASE_PASSWORD";
    public static final String QUEUE_CAPACITY_ENV = "DB_WRITER_QUEUE_CAPACITY";
    public static final String BATCH_SIZE_ENV = "DB_WRITER_BATCH_SIZE";
    public static final int DEFAULT_QUEUE_CAPACITY = 250_000;
    public static final int DEFAULT_BATCH_SIZE = 500;

    public DbWriterConfig {
        databaseUrl = normalize(databaseUrl);
        databaseUser = normalize(databaseUser);
        databasePassword = normalize(databasePassword);
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
            parseBoolean(env.get(ENABLED_ENV), false),
            env.get(DATABASE_URL_ENV),
            env.get(DATABASE_USER_ENV),
            env.get(DATABASE_PASSWORD_ENV),
            parseInt(env.get(QUEUE_CAPACITY_ENV), DEFAULT_QUEUE_CAPACITY, QUEUE_CAPACITY_ENV),
            parseInt(env.get(BATCH_SIZE_ENV), DEFAULT_BATCH_SIZE, BATCH_SIZE_ENV)
        );
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
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
}
