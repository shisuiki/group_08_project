package edu.illinois.group8.frontend;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PrometheusHotPathLatencyReader implements Supplier<HotPathLatencyStatus> {
    static final String BACKEND_METRICS_URLS_ENV = "FRONTEND_ADAPTER_BACKEND_METRICS_URLS";
    static final String BACKEND_METRICS_URL_ENV = "FRONTEND_ADAPTER_BACKEND_METRICS_URL";
    static final String FEATUREPLANT_METRICS_URL_ENV = "FRONTEND_ADAPTER_FEATUREPLANT_METRICS_URL";
    static final String TIMEOUT_MS_ENV = "FRONTEND_ADAPTER_HOT_PATH_METRICS_TIMEOUT_MS";

    private static final String WS_TO_TICKERPLANT_METRIC = "backend_hot_path_ws_to_tickerplant_publish_ns";
    private static final String FEATUREPLANT_CONSUMER_METRIC = "featureplant_hot_path_consumer_to_module_complete_ns";
    private static final String FEATURE_MODULE_METRIC = "feature_module_latency_ns";
    private static final int DEFAULT_TIMEOUT_MS = 750;
    private static final int MAX_SERIES_PER_STAGE = 12;
    private static final Pattern PROMETHEUS_LINE = Pattern.compile(
        "^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{([^}]*)})?\\s+([-+0-9.eE]+)$"
    );

    private final HttpClient client;
    private final List<URI> backendMetricsUris;
    private final URI featureplantMetricsUri;
    private final Duration timeout;

    private PrometheusHotPathLatencyReader(
        HttpClient client,
        List<URI> backendMetricsUris,
        URI featureplantMetricsUri,
        Duration timeout
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.backendMetricsUris = backendMetricsUris == null ? List.of() : List.copyOf(backendMetricsUris);
        this.featureplantMetricsUri = featureplantMetricsUri;
        this.timeout = timeout == null ? Duration.ofMillis(DEFAULT_TIMEOUT_MS) : timeout;
    }

    static Supplier<HotPathLatencyStatus> fromEnvironment(Map<String, String> env) {
        List<URI> backendUrls = urisOrEmpty(value(
            env,
            BACKEND_METRICS_URLS_ENV,
            value(env, BACKEND_METRICS_URL_ENV, "")
        ));
        URI featureplantUrl = uriOrNull(value(env, FEATUREPLANT_METRICS_URL_ENV, ""));
        if (backendUrls.isEmpty() && featureplantUrl == null) {
            return HotPathLatencyStatus::disabled;
        }
        int timeoutMs = positiveInt(value(env, TIMEOUT_MS_ENV, Integer.toString(DEFAULT_TIMEOUT_MS)));
        return new PrometheusHotPathLatencyReader(
            HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build(),
            backendUrls,
            featureplantUrl,
            Duration.ofMillis(timeoutMs)
        );
    }

    static HotPathLatencyStatus fromPrometheusTexts(String backendPrometheus, String featureplantPrometheus) {
        return statusFromTexts(backendPrometheus, featureplantPrometheus, List.of());
    }

    @Override
    public HotPathLatencyStatus get() {
        List<String> errors = new ArrayList<>();
        String backendText = String.join("\n", fetchAll("backend", backendMetricsUris, errors));
        String featureplantText = fetch("featureplant", featureplantMetricsUri, errors);
        return statusFromTexts(backendText, featureplantText, errors);
    }

    private List<String> fetchAll(String name, List<URI> uris, List<String> errors) {
        if (uris == null || uris.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < uris.size(); i++) {
            URI uri = uris.get(i);
            String fetchName = name + "[" + i + "]";
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "text/plain")
                .GET()
                .build();
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> responseBody(fetchName, response, error, errors)));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            addError(errors, name + " metrics timed out after " + timeout.toMillis() + "ms");
            futures.forEach(future -> future.cancel(true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            addError(errors, name + " metrics interrupted");
            futures.forEach(future -> future.cancel(true));
        } catch (java.util.concurrent.ExecutionException e) {
            addError(errors, name + " metrics unavailable: " + e.getMessage());
        }
        List<String> bodies = new ArrayList<>();
        for (CompletableFuture<String> future : futures) {
            if (future.isDone() && !future.isCancelled()) {
                bodies.add(future.join());
            }
        }
        return bodies;
    }

    private String fetch(String name, URI uri, List<String> errors) {
        if (uri == null) {
            return "";
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "text/plain")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                errors.add(name + " metrics returned HTTP " + response.statusCode());
                return "";
            }
            return response.body();
        } catch (IOException e) {
            errors.add(name + " metrics unavailable: " + e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.add(name + " metrics interrupted");
            return "";
        } catch (RuntimeException e) {
            errors.add(name + " metrics unavailable: " + e.getMessage());
            return "";
        }
    }

    private static String responseBody(
        String name,
        HttpResponse<String> response,
        Throwable error,
        List<String> errors
    ) {
        if (error != null) {
            addError(errors, name + " metrics unavailable: " + error.getMessage());
            return "";
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            addError(errors, name + " metrics returned HTTP " + response.statusCode());
            return "";
        }
        return response.body();
    }

    private static void addError(List<String> errors, String error) {
        synchronized (errors) {
            errors.add(error);
        }
    }

    private static HotPathLatencyStatus statusFromTexts(
        String backendPrometheus,
        String featureplantPrometheus,
        List<String> errors
    ) {
        List<HotPathLatencyStatus.Stage> stages = new ArrayList<>();
        stages.add(HotPathLatencyStatus.Stage.ok(
            "ws_to_tickerplant_publish",
            "WS receive to tickerplant publish",
            "backend",
            WS_TO_TICKERPLANT_METRIC,
            "Measured from wsclient receive_ts_ns to backend canonical publisher offer completion.",
            topSeries(parseDistributionSeries(backendPrometheus, WS_TO_TICKERPLANT_METRIC), null)
        ));
        stages.add(HotPathLatencyStatus.Stage.ok(
            "featureplant_consumer_to_bbo_complete",
            "Tickerplant consumer receive to BBO module complete",
            "featureplant",
            FEATUREPLANT_CONSUMER_METRIC,
            "Measured only when live Aeron consumer receive_ts_ns is present.",
            topSeries(parseDistributionSeries(featureplantPrometheus, FEATUREPLANT_CONSUMER_METRIC), "feature.bbo")
        ));
        stages.add(HotPathLatencyStatus.Stage.ok(
            "featureplant_bbo_module_processing",
            "FeaturePlant BBO module processing",
            "featureplant",
            FEATURE_MODULE_METRIC,
            "Module execution latency; excludes DB projection and frontend rendering.",
            topSeries(parseDistributionSeries(featureplantPrometheus, FEATURE_MODULE_METRIC), "feature.bbo")
        ));
        return HotPathLatencyStatus.fromStages(stages, errors);
    }

    private static List<HotPathLatencyStatus.Series> topSeries(
        List<HotPathLatencyStatus.Series> series,
        String moduleFilter
    ) {
        return series.stream()
            .filter(item -> moduleFilter == null || moduleFilter.equals(item.labels().get("module")))
            .filter(item -> item.count() > 0L)
            .sorted(Comparator
                .comparingLong(HotPathLatencyStatus.Series::recentCount).reversed()
                .thenComparing(item -> item.labels().getOrDefault("stream", "")))
            .limit(MAX_SERIES_PER_STAGE)
            .toList();
    }

    private static List<HotPathLatencyStatus.Series> parseDistributionSeries(String prometheus, String metricName) {
        Map<Map<String, String>, DistributionAccumulator> accumulators = new LinkedHashMap<>();
        if (prometheus == null || prometheus.isBlank()) {
            return List.of();
        }
        for (String line : prometheus.split("\\R")) {
            Matcher matcher = PROMETHEUS_LINE.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            Optional<Suffix> suffix = Suffix.fromMetricName(metricName, matcher.group(1));
            if (suffix.isEmpty()) {
                continue;
            }
            Map<String, String> labels = parseLabels(matcher.group(2));
            long value = Math.round(Double.parseDouble(matcher.group(3)));
            accumulators.computeIfAbsent(labels, ignored -> new DistributionAccumulator()).put(suffix.get(), value);
        }
        return accumulators.entrySet().stream()
            .map(entry -> entry.getValue().toSeries(entry.getKey()))
            .toList();
    }

    private static Map<String, String> parseLabels(String rawLabels) {
        if (rawLabels == null || rawLabels.isBlank()) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        int index = 0;
        while (index < rawLabels.length()) {
            int equals = rawLabels.indexOf('=', index);
            if (equals < 0) {
                break;
            }
            String key = rawLabels.substring(index, equals).trim();
            int quoteStart = rawLabels.indexOf('"', equals + 1);
            if (quoteStart < 0) {
                break;
            }
            StringBuilder value = new StringBuilder();
            int cursor = quoteStart + 1;
            boolean escaping = false;
            while (cursor < rawLabels.length()) {
                char ch = rawLabels.charAt(cursor++);
                if (escaping) {
                    value.append(switch (ch) {
                        case 'n' -> '\n';
                        case '\\' -> '\\';
                        case '"' -> '"';
                        default -> ch;
                    });
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    break;
                } else {
                    value.append(ch);
                }
            }
            if (!key.isBlank()) {
                labels.put(key, value.toString());
            }
            index = cursor;
            if (index < rawLabels.length() && rawLabels.charAt(index) == ',') {
                index++;
            }
        }
        return Map.copyOf(labels);
    }

    private static URI uriOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return URI.create(raw.trim());
    }

    private static List<URI> urisOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<URI> uris = new ArrayList<>();
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isBlank()) {
                uris.add(URI.create(trimmed));
            }
        }
        return List.copyOf(uris);
    }

    private static String value(Map<String, String> env, String key, String defaultValue) {
        String value = env == null ? null : env.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int positiveInt(String raw) {
        int parsed = Integer.parseInt(raw);
        if (parsed < 1) {
            throw new IllegalArgumentException(TIMEOUT_MS_ENV + " must be positive");
        }
        return parsed;
    }

    private enum Suffix {
        RECENT_COUNT("_recent_count"),
        RECENT_P50("_recent_p50"),
        RECENT_P90("_recent_p90"),
        RECENT_P95("_recent_p95"),
        RECENT_P99("_recent_p99"),
        RECENT_P999("_recent_p999"),
        COUNT("_count"),
        SUM("_sum"),
        MAX("_max");

        private final String value;

        Suffix(String value) {
            this.value = value;
        }

        private static Optional<Suffix> fromMetricName(String metricName, String name) {
            for (Suffix suffix : values()) {
                if (name.equals(metricName + suffix.value)) {
                    return Optional.of(suffix);
                }
            }
            return Optional.empty();
        }
    }

    private static final class DistributionAccumulator {
        private long count;
        private long sum;
        private long max;
        private long recentCount;
        private Long p50;
        private Long p90;
        private Long p95;
        private Long p99;
        private Long p999;

        private void put(Suffix suffix, long value) {
            switch (suffix) {
                case COUNT -> count += value;
                case SUM -> sum += value;
                case MAX -> max = Math.max(max, value);
                case RECENT_COUNT -> recentCount += value;
                case RECENT_P50 -> p50 = p50 == null ? value : Math.max(p50, value);
                case RECENT_P90 -> p90 = p90 == null ? value : Math.max(p90, value);
                case RECENT_P95 -> p95 = p95 == null ? value : Math.max(p95, value);
                case RECENT_P99 -> p99 = p99 == null ? value : Math.max(p99, value);
                case RECENT_P999 -> p999 = p999 == null ? value : Math.max(p999, value);
            }
        }

        private HotPathLatencyStatus.Series toSeries(Map<String, String> labels) {
            Long avg = count <= 0L ? null : sum / count;
            return new HotPathLatencyStatus.Series(labels, count, recentCount, p50, p90, p95, p99, p999, max, avg);
        }
    }
}
