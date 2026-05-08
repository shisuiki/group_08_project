package edu.illinois.group8.replay.raw;

import java.util.Locale;

public final class RawReplaySourceFactory {
    private RawReplaySourceFactory() {
    }

    public static RawReplaySource fromConfig(RawIngressReplayConfig config) {
        String source = config.source().trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (source) {
            case "timescale", "timescaledb", "postgres", "postgresql" -> new TimescaleRawReplaySource(config);
            case "local-ndjson", "ndjson" -> new LocalNdjsonRawReplaySource(config.localRawRecordingRoot());
            default -> throw new IllegalArgumentException("Unknown RAW_REPLAY_SOURCE: " + config.source());
        };
    }
}
