package edu.illinois.group8.time;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class TimestampSource {
    public enum Mode {
        SYSTEM_NANO,
        EPOCH_NANOS
    }

    private final Mode mode;
    private final String configuredValue;
    private final String ptpDevice;

    private TimestampSource(Mode mode, String configuredValue, String ptpDevice) {
        this.mode = mode;
        this.configuredValue = configuredValue;
        this.ptpDevice = ptpDevice == null || ptpDevice.isBlank() ? "/dev/ptp_ena" : ptpDevice;
    }

    public static TimestampSource fromEnvironment() {
        String value = firstNonBlank(
            System.getenv("RAW_INGEST_RECORDER_TIMESTAMP_SOURCE"),
            System.getenv("STREAM_RECORDER_TIMESTAMP_SOURCE"),
            System.getenv("FRONTEND_ADAPTER_TIMESTAMP_SOURCE"),
            System.getenv("INSTRUMENTATION_TIMESTAMP_SOURCE"),
            System.getenv("BACKEND_TIMESTAMP_SOURCE"),
            System.getenv("GATEWAY_TIMESTAMP_SOURCE"),
            "system_nano"
        );
        String ptpDevice = firstNonBlank(System.getenv("EC2_PTP_DEVICE"), "/dev/ptp_ena");
        return from(value, ptpDevice);
    }

    public static TimestampSource from(String value, String ptpDevice) {
        String normalized = value == null ? "system_nano" : value.trim().toLowerCase(Locale.ROOT);
        Mode mode = switch (normalized) {
            case "epoch", "epoch_nanos", "ptp", "ptp_system_clock" -> Mode.EPOCH_NANOS;
            case "system", "system_nano", "monotonic" -> Mode.SYSTEM_NANO;
            default -> throw new IllegalArgumentException("Unsupported timestamp source: " + value);
        };
        return new TimestampSource(mode, normalized, ptpDevice);
    }

    public long nowNanos() {
        if (mode == Mode.EPOCH_NANOS) {
            Instant now = Instant.now();
            return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
        }
        return System.nanoTime();
    }

    public Instant nowInstant() {
        return Instant.now();
    }

    public Map<String, Object> metadata() {
        boolean deviceVisible = java.nio.file.Files.exists(java.nio.file.Path.of(ptpDevice));
        boolean hostPtpEnabled = Boolean.parseBoolean(firstNonBlank(
            System.getenv("EC2_PTP_ENABLED"),
            System.getenv("ENABLE_EC2_PTP"),
            "false"
        ));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("configured", configuredValue);
        metadata.put("mode", mode.name().toLowerCase(Locale.ROOT));
        metadata.put("ptp_device", ptpDevice);
        metadata.put("ptp_device_present", deviceVisible || hostPtpEnabled);
        metadata.put("ptp_device_visible_to_process", deviceVisible);
        metadata.put("ptp_host_enabled", hostPtpEnabled);
        metadata.put("note", mode == Mode.EPOCH_NANOS
            ? "Uses the OS wall clock in nanoseconds. On EC2, configure chrony to discipline the system clock from PHC/PTP before enabling this."
            : "Uses JVM monotonic nanoTime for latency-safe relative timestamps.");
        return metadata;
    }

    public Mode mode() {
        return mode;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
