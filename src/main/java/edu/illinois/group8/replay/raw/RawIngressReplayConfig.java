package edu.illinois.group8.replay.raw;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public record RawIngressReplayConfig(
    Path rawRecordingRoot,
    RawReplayMode mode,
    double speedMultiplier,
    long fixedRatePerSecond,
    long maxEvents,
    boolean dryRun,
    String replayId
) {
    public static RawIngressReplayConfig fromEnvironment() {
        Map<String, String> env = System.getenv();
        String baseDir = value(env, "BASE_DIR", "/app");
        return new RawIngressReplayConfig(
            Path.of(value(env, "RAW_REPLAY_ROOT", baseDir + "/recordings/raw-ingest")),
            mode(value(env, "RAW_REPLAY_MODE", "as-fast-as-possible")),
            doubleValue(env, "RAW_REPLAY_SPEED_MULTIPLIER", 1.0),
            longValue(env, "RAW_REPLAY_FIXED_RATE_PER_SECOND", 0L),
            longValue(env, "RAW_REPLAY_MAX_EVENTS", 0L),
            booleanValue(env, "RAW_REPLAY_DRY_RUN", false),
            value(env, "RAW_REPLAY_ID", "raw-replay-" + UUID.randomUUID())
        );
    }

    public RawIngressReplayConfig withCliArgs(String[] args) {
        Path selectedRoot = rawRecordingRoot;
        RawReplayMode selectedMode = mode;
        double selectedSpeed = speedMultiplier;
        long selectedFixedRate = fixedRatePerSecond;
        long selectedMaxEvents = maxEvents;
        boolean selectedDryRun = dryRun;
        String selectedReplayId = replayId;
        for (String arg : args) {
            if (arg.startsWith("--root=")) {
                selectedRoot = Path.of(arg.substring("--root=".length()));
            } else if (arg.startsWith("--mode=")) {
                selectedMode = mode(arg.substring("--mode=".length()));
            } else if (arg.startsWith("--speed=")) {
                selectedSpeed = Double.parseDouble(arg.substring("--speed=".length()));
            } else if (arg.startsWith("--fixed-rate=")) {
                selectedFixedRate = Long.parseLong(arg.substring("--fixed-rate=".length()));
                selectedMode = RawReplayMode.FIXED_RATE;
            } else if (arg.startsWith("--max-events=")) {
                selectedMaxEvents = Long.parseLong(arg.substring("--max-events=".length()));
            } else if (arg.equals("--dry-run")) {
                selectedDryRun = true;
            } else if (arg.startsWith("--replay-id=")) {
                selectedReplayId = arg.substring("--replay-id=".length());
            }
        }
        return new RawIngressReplayConfig(
            selectedRoot,
            selectedMode,
            selectedSpeed,
            selectedFixedRate,
            selectedMaxEvents,
            selectedDryRun,
            selectedReplayId
        );
    }

    private static String value(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static boolean booleanValue(Map<String, String> env, String key, boolean defaultValue) {
        String raw = value(env, key, Boolean.toString(defaultValue));
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static long longValue(Map<String, String> env, String key, long defaultValue) {
        String raw = value(env, key, Long.toString(defaultValue));
        return Long.parseLong(raw);
    }

    private static double doubleValue(Map<String, String> env, String key, double defaultValue) {
        String raw = value(env, key, Double.toString(defaultValue));
        return Double.parseDouble(raw);
    }

    private static RawReplayMode mode(String raw) {
        return RawReplayMode.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
