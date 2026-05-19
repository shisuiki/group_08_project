package edu.illinois.group8.storage.db;

import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;

public final class DbReleasePreflightCli {
    private DbReleasePreflightCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.getenv(), System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Config config;
        try {
            config = Config.from(args, env);
        } catch (IllegalArgumentException exc) {
            err.println("FAIL db_release_preflight config_error=" + exc.getMessage());
            return 1;
        }
        if (config.helpRequested()) {
            out.print(usage());
            return 0;
        }
        if (config.dbUrl().isBlank()) {
            if (config.required()) {
                err.println("FAIL db_release_preflight DB URL is empty and required=true");
                return 2;
            }
            out.println("SKIP db_release_preflight DB URL is empty and required=false");
            return 0;
        }
        return run(
            config,
            JdbcConnectionFactories.fromDriverManager(config.dbUrl(), config.dbUser(), config.dbPassword()),
            out,
            err
        );
    }

    static int run(Config config, JdbcConnectionFactory connectionFactory, PrintStream out, PrintStream err) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(connectionFactory, "connectionFactory");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        try {
            DbReleasePreflightResult result = new DbReleasePreflightCheck(connectionFactory).run();
            out.println("PASS db_release_preflight");
            for (String line : result.lines()) {
                out.println(line);
            }
            return 0;
        } catch (IllegalStateException exc) {
            err.println("FAIL db_release_preflight " + exc.getMessage());
            return 2;
        }
    }

    static String usage() {
        return """
            Usage: DbReleasePreflightCli [options]

            Options:
              --db-url=<jdbc-url>        Overrides DB_PREFLIGHT_DATABASE_URL / DB_WRITER_DATABASE_URL
              --db-user=<user>           Overrides DB_PREFLIGHT_DATABASE_USER / DB_WRITER_DATABASE_USER
              --db-password=<password>   Overrides DB_PREFLIGHT_DATABASE_PASSWORD / DB_WRITER_DATABASE_PASSWORD
              --required=true|false      Overrides DEPLOY_DB_PREFLIGHT_REQUIRED
              --help                     Show this message
            """;
    }

    record Config(
        String dbUrl,
        String dbUser,
        String dbPassword,
        boolean required,
        boolean helpRequested
    ) {
        static Config from(String[] args, Map<String, String> env) {
            String dbUrl = envValue(env, "DB_PREFLIGHT_DATABASE_URL", envValue(env, "DB_WRITER_DATABASE_URL", ""));
            String dbUser = envValue(env, "DB_PREFLIGHT_DATABASE_USER", envValue(env, "DB_WRITER_DATABASE_USER", ""));
            String dbPassword = envValue(
                env,
                "DB_PREFLIGHT_DATABASE_PASSWORD",
                envValue(env, "DB_WRITER_DATABASE_PASSWORD", "")
            );
            boolean required = booleanValue(envValue(env, "DEPLOY_DB_PREFLIGHT_REQUIRED", "false"),
                "DEPLOY_DB_PREFLIGHT_REQUIRED");
            boolean helpRequested = false;

            for (String arg : args) {
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    helpRequested = true;
                } else if (arg.startsWith("--db-url=")) {
                    dbUrl = arg.substring("--db-url=".length());
                } else if (arg.startsWith("--db-user=")) {
                    dbUser = arg.substring("--db-user=".length());
                } else if (arg.startsWith("--db-password=")) {
                    dbPassword = arg.substring("--db-password=".length());
                } else if (arg.startsWith("--required=")) {
                    required = booleanValue(arg.substring("--required=".length()), "--required");
                } else {
                    throw new IllegalArgumentException("Unknown DbReleasePreflightCli option: " + arg);
                }
            }
            return new Config(normalize(dbUrl), normalize(dbUser), normalize(dbPassword), required, helpRequested);
        }

        private static String envValue(Map<String, String> env, String key, String fallback) {
            String value = env.get(key);
            return value == null || value.isBlank() ? fallback : value;
        }

        private static boolean booleanValue(String value, String name) {
            String normalized = normalize(value).toLowerCase();
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || normalized.isBlank()) {
                return false;
            }
            throw new IllegalArgumentException(name + " must be true or false.");
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
