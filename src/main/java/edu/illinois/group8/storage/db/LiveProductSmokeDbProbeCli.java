package edu.illinois.group8.storage.db;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class LiveProductSmokeDbProbeCli {
    private LiveProductSmokeDbProbeCli() {
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
            err.println("FAIL live_product_smoke_db_probe config_error=" + exc.getMessage());
            return 1;
        }
        if (config.helpRequested()) {
            out.print(usage());
            return 0;
        }
        if (config.dbUrl().isBlank()) {
            err.println("FAIL live_product_smoke_db_probe DB URL is empty");
            return 2;
        }
        return run(
            config,
            JdbcConnectionFactories.fromDriverManager(config.dbUrl(), config.dbUser(), config.dbPassword()),
            out,
            err
        );
    }

    static int run(
        Config config,
        JdbcConnectionFactory connectionFactory,
        PrintStream out,
        PrintStream err
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(connectionFactory, "connectionFactory");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        try {
            LiveProductSmokeDbProbe probe = new LiveProductSmokeDbProbe(connectionFactory);
            switch (config.command()) {
                case "cursorCommitSeq" -> out.println(probe.cursorCommitSeq(required(config, "cursor-name")));
                case "maxCanonicalCommitSeq" -> out.println(probe.maxCanonicalCommitSeq());
                case "seedCanonicalEvents" -> {
                    LiveProductSmokeDbProbe.SeedResult result = probe.seedCanonicalEvents(
                        required(config, "run-id"),
                        required(config, "market-ticker"),
                        required(config, "prefix")
                    );
                    out.println(result.seededCount() + "|" + result.targetCommitSeq());
                }
                case "featureOutputsForPrefix" -> {
                    LiveProductSmokeDbProbe.FeatureOutputProgress progress = probe.featureOutputsForPrefix(
                        required(config, "prefix"),
                        required(config, "cursor-name")
                    );
                    out.println(progress.featureOutputCount() + "|" + progress.cursorCommitSeq());
                }
                case "recentNonSmokeCanonicalEvents" -> out.println(probe.recentNonSmokeCanonicalEvents());
                case "latestNonSmokeCanonicalAfter" -> {
                    LiveProductSmokeDbProbe.CanonicalEventSummary event = probe.latestNonSmokeCanonicalAfter(
                        requiredLong(config, "after-commit-seq")
                    );
                    if (event != null) {
                        out.println(event.eventId() + "|" + event.marketTicker() + "|" + event.streamName()
                            + "|" + event.commitSeq() + "|" + event.eventTsMs());
                    }
                }
                case "featureOutputsForSourceEvent" -> {
                    LiveProductSmokeDbProbe.FeatureOutputSummary summary = probe.featureOutputsForSourceEvent(
                        required(config, "source-event-id")
                    );
                    out.println(summary.count() + "|" + summary.featureName() + "|" + summary.marketTicker()
                        + "|" + summary.eventTsMs() + "|" + summary.sourceEventId());
                }
                case "latestNonSmokeFeatureOutputAfter" -> {
                    LiveProductSmokeDbProbe.LiveFeatureOutputSummary output = probe.latestNonSmokeFeatureOutputAfter(
                        requiredLong(config, "after-commit-seq")
                    );
                    if (output != null) {
                        out.println(output.sourceEventId() + "|" + output.featureName() + "|" + output.marketTicker()
                            + "|" + output.eventTsMs() + "|" + output.streamName() + "|" + output.commitSeq());
                    }
                }
                default -> throw new IllegalArgumentException("Unknown command: " + config.command());
            }
            return 0;
        } catch (Exception exc) {
            err.println("FAIL live_product_smoke_db_probe " + exc.getMessage());
            return 2;
        }
    }

    static String usage() {
        return """
            Usage: LiveProductSmokeDbProbeCli <command> [options]

            Commands:
              cursorCommitSeq --cursor-name=<name>
              maxCanonicalCommitSeq
              seedCanonicalEvents --run-id=<id> --market-ticker=<ticker> --prefix=<prefix>
              featureOutputsForPrefix --prefix=<prefix> --cursor-name=<name>
              recentNonSmokeCanonicalEvents
              latestNonSmokeCanonicalAfter --after-commit-seq=<seq>
              featureOutputsForSourceEvent --source-event-id=<event-id>
              latestNonSmokeFeatureOutputAfter --after-commit-seq=<seq>

            DB options:
              --db-url=<jdbc-url>        Overrides LIVE_PRODUCT_SMOKE_DB_URL / DB_WRITER_DATABASE_URL
              --db-user=<user>           Overrides LIVE_PRODUCT_SMOKE_DB_USER / DB_WRITER_DATABASE_USER
              --db-password=<password>   Overrides LIVE_PRODUCT_SMOKE_DB_PASSWORD / DB_WRITER_DATABASE_PASSWORD
              --help                     Show this message
            """;
    }

    private static String required(Config config, String option) {
        String value = config.options().get(option);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + option + " is required for " + config.command());
        }
        return value.trim();
    }

    private static long requiredLong(Config config, String option) {
        String value = required(config, option);
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("--" + option + " must be non-negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " must be a long integer");
        }
    }

    record Config(
        String command,
        String dbUrl,
        String dbUser,
        String dbPassword,
        Map<String, String> options,
        boolean helpRequested
    ) {
        static Config from(String[] args, Map<String, String> env) {
            boolean helpRequested = false;
            String command = "";
            String dbUrl = firstEnv(
                env,
                "LIVE_PRODUCT_SMOKE_DB_URL",
                "DB_WRITER_DATABASE_URL",
                "FEATUREPLANT_DB_URL",
                "FRONTEND_ADAPTER_DB_URL"
            );
            String dbUser = firstEnv(
                env,
                "LIVE_PRODUCT_SMOKE_DB_USER",
                "DB_WRITER_DATABASE_USER",
                "FEATUREPLANT_DB_USER",
                "FRONTEND_ADAPTER_DB_USER"
            );
            String dbPassword = firstEnv(
                env,
                "LIVE_PRODUCT_SMOKE_DB_PASSWORD",
                "DB_WRITER_DATABASE_PASSWORD",
                "FEATUREPLANT_DB_PASSWORD",
                "FRONTEND_ADAPTER_DB_PASSWORD"
            );
            Map<String, String> options = new HashMap<>();

            for (String arg : args) {
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    helpRequested = true;
                } else if (arg.startsWith("--db-url=")) {
                    dbUrl = arg.substring("--db-url=".length());
                } else if (arg.startsWith("--db-user=")) {
                    dbUser = arg.substring("--db-user=".length());
                } else if (arg.startsWith("--db-password=")) {
                    dbPassword = arg.substring("--db-password=".length());
                } else if (arg.startsWith("--")) {
                    int separator = arg.indexOf('=');
                    if (separator <= 2) {
                        throw new IllegalArgumentException("Option must use --name=value: " + arg);
                    }
                    options.put(arg.substring(2, separator), arg.substring(separator + 1));
                } else if (command.isBlank()) {
                    command = arg;
                } else {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
            }

            return new Config(
                normalize(command),
                normalize(dbUrl),
                normalize(dbUser),
                normalize(dbPassword),
                Map.copyOf(options),
                helpRequested
            );
        }

        private static String firstEnv(Map<String, String> env, String... keys) {
            for (String key : keys) {
                String value = env.get(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
