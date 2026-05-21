package edu.illinois.group8.frontend;

import java.util.List;
import java.util.Map;

public record HotPathLatencyStatus(
    String status,
    String source,
    String note,
    List<Stage> stages,
    String error
) {
    public HotPathLatencyStatus {
        status = normalize(status, "unknown");
        source = normalize(source, "");
        note = normalize(note, "");
        stages = stages == null ? List.of() : List.copyOf(stages);
        error = normalize(error, null);
    }

    public static HotPathLatencyStatus disabled() {
        return new HotPathLatencyStatus(
            "disabled",
            "",
            "Configure frontend adapter metrics URLs to read live hot-path latency.",
            List.of(),
            null
        );
    }

    public static HotPathLatencyStatus unavailable(String error) {
        return new HotPathLatencyStatus(
            "unavailable",
            "prometheus",
            "Hot-path latency metrics unavailable.",
            List.of(),
            error
        );
    }

    public static HotPathLatencyStatus fromStages(List<Stage> stages, List<String> errors) {
        List<Stage> safeStages = stages == null ? List.of() : List.copyOf(stages);
        List<String> safeErrors = errors == null ? List.of() : errors.stream()
            .filter(value -> value != null && !value.isBlank())
            .toList();
        boolean hasOkStage = safeStages.stream().anyMatch(stage -> "ok".equals(stage.status()));
        String status;
        if (hasOkStage && safeErrors.isEmpty()) {
            status = "ok";
        } else if (hasOkStage) {
            status = "partial";
        } else if (safeErrors.isEmpty()) {
            status = "missing";
        } else {
            status = "unavailable";
        }
        return new HotPathLatencyStatus(
            status,
            "prometheus",
            "Hot-path latency excludes Kalshi CDN/network before wsclient receive and DB read-model projection.",
            safeStages,
            safeErrors.isEmpty() ? null : String.join("; ", safeErrors)
        );
    }

    public record Stage(
        String id,
        String label,
        String status,
        String source,
        String metric,
        String note,
        List<Series> series
    ) {
        public Stage {
            id = normalize(id, "");
            label = normalize(label, id);
            status = normalize(status, "unknown");
            source = normalize(source, "");
            metric = normalize(metric, "");
            note = normalize(note, "");
            series = series == null ? List.of() : List.copyOf(series);
        }

        static Stage ok(
            String id,
            String label,
            String source,
            String metric,
            String note,
            List<Series> series
        ) {
            List<Series> safeSeries = series == null ? List.of() : List.copyOf(series);
            return new Stage(
                id,
                label,
                safeSeries.isEmpty() ? "missing" : "ok",
                source,
                metric,
                note,
                safeSeries
            );
        }
    }

    public record Series(
        Map<String, String> labels,
        long count,
        long recentCount,
        Long p50Ns,
        Long p90Ns,
        Long p95Ns,
        Long p99Ns,
        Long p999Ns,
        Long maxNs,
        Long avgNs
    ) {
        public Series {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
