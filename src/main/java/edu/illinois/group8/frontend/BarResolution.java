package edu.illinois.group8.frontend;

import java.util.List;
import java.util.Locale;

public enum BarResolution {
    S1("1S", 1_000L),
    S5("5S", 5_000L),
    S30("30S", 30_000L),
    M1("1", 60_000L),
    M5("5", 300_000L),
    M15("15", 900_000L),
    H1("60", 3_600_000L);

    public static final List<String> SUPPORTED = List.of("1S", "5S", "30S", "1", "5", "15", "60");

    private final String token;
    private final long bucketSizeMs;

    BarResolution(String token, long bucketSizeMs) {
        this.token = token;
        this.bucketSizeMs = bucketSizeMs;
    }

    public String token() {
        return token;
    }

    public long bucketSizeMs() {
        return bucketSizeMs;
    }

    public static BarResolution parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("resolution is required");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "1S" -> S1;
            case "5S" -> S5;
            case "30S" -> S30;
            case "1", "M1" -> M1;
            case "5", "M5" -> M5;
            case "15", "M15" -> M15;
            case "60", "H1", "1H" -> H1;
            default -> throw new IllegalArgumentException("Unsupported TradingView resolution: " + raw);
        };
    }
}
