package edu.illinois.group8.wrapper;

import edu.illinois.group8.ingress.KalshiIngressEnvelope;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.DbOfferResult;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

final class KalshiInboundMessageHandler {
    static final int HOT_PATH_DISTRIBUTION_SAMPLE_MASK = 63;
    static final String RECEIVE_TO_CLUSTER_OFFER_METRIC = "wsclient_hot_path_receive_to_cluster_offer_ns";

    private final ClusterWriter clusterWriter;
    private final RawRecorder rawRecorder;
    private final RawDbRecorder rawDbRecorder;
    private final AckCallbacks ackCallbacks;
    private final String connectionId;
    private final BackendMetrics metrics;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<ClusterOfferMetricKey, BackendMetrics.DistributionHandle> clusterOfferLatencyHandles =
        new ConcurrentHashMap<>();
    private long clusterOfferSampleCursor;

    KalshiInboundMessageHandler(
        ClusterWriter clusterWriter,
        RawRecorder rawRecorder,
        RawDbRecorder rawDbRecorder,
        AckCallbacks ackCallbacks,
        String connectionId
    ) {
        this(clusterWriter, rawRecorder, rawDbRecorder, ackCallbacks, connectionId, new BackendMetrics(), System::nanoTime);
    }

    KalshiInboundMessageHandler(
        ClusterWriter clusterWriter,
        RawRecorder rawRecorder,
        RawDbRecorder rawDbRecorder,
        AckCallbacks ackCallbacks,
        String connectionId,
        BackendMetrics metrics
    ) {
        this(clusterWriter, rawRecorder, rawDbRecorder, ackCallbacks, connectionId, metrics, System::nanoTime);
    }

    KalshiInboundMessageHandler(
        ClusterWriter clusterWriter,
        RawRecorder rawRecorder,
        RawDbRecorder rawDbRecorder,
        AckCallbacks ackCallbacks,
        String connectionId,
        BackendMetrics metrics,
        LongSupplier nanoTime
    ) {
        this.clusterWriter = Objects.requireNonNull(clusterWriter, "clusterWriter");
        this.rawRecorder = Objects.requireNonNull(rawRecorder, "rawRecorder");
        this.rawDbRecorder = Objects.requireNonNull(rawDbRecorder, "rawDbRecorder");
        this.ackCallbacks = Objects.requireNonNull(ackCallbacks, "ackCallbacks");
        this.connectionId = connectionId;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    void handleInbound(String rawPayload, long receiveTsNs, Instant receiveWallTs) {
        byte[] clusterPayload = KalshiIngressEnvelope.wrapBytes(rawPayload, receiveTsNs, receiveWallTs, connectionId, null);
        boolean sampleClusterOffer = shouldSampleClusterOfferLatency();
        boolean accepted = clusterWriter.write(clusterPayload);
        if (sampleClusterOffer) {
            clusterOfferLatency(messageTypeLabel(rawPayload), accepted)
                .observe(Math.max(0L, nanoTime.getAsLong() - receiveTsNs));
        }
        recordRaw(rawPayload, receiveTsNs, receiveWallTs);
        recordRawDb(rawPayload, receiveTsNs, receiveWallTs);
        handleAck(rawPayload);
    }

    private BackendMetrics.DistributionHandle clusterOfferLatency(String messageType, boolean accepted) {
        ClusterOfferMetricKey key = new ClusterOfferMetricKey(messageType, accepted ? "accepted" : "dropped");
        return clusterOfferLatencyHandles.computeIfAbsent(
            key,
            item -> metrics.distribution(
                RECEIVE_TO_CLUSTER_OFFER_METRIC,
                BackendMetrics.labels(
                    "service", "wsclient",
                    "source", "kalshi",
                    "message_type", item.messageType(),
                    "result", item.result()
                )
            )
        );
    }

    private boolean shouldSampleClusterOfferLatency() {
        return (clusterOfferSampleCursor++ & HOT_PATH_DISTRIBUTION_SAMPLE_MASK) == 0L;
    }

    static String messageTypeLabel(String rawPayload) {
        String type = firstJsonStringField(rawPayload, "type");
        return type == null || type.isBlank() ? "unknown" : type;
    }

    private static String firstJsonStringField(String rawPayload, String fieldName) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return null;
        }
        String needle = "\"" + fieldName + "\"";
        int keyIndex = rawPayload.indexOf(needle);
        if (keyIndex < 0) {
            return null;
        }
        int colonIndex = rawPayload.indexOf(':', keyIndex + needle.length());
        if (colonIndex < 0) {
            return null;
        }
        int valueStart = colonIndex + 1;
        while (valueStart < rawPayload.length() && Character.isWhitespace(rawPayload.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= rawPayload.length() || rawPayload.charAt(valueStart) != '"') {
            return null;
        }
        StringBuilder value = new StringBuilder();
        boolean escaping = false;
        for (int cursor = valueStart + 1; cursor < rawPayload.length(); cursor++) {
            char ch = rawPayload.charAt(cursor);
            if (escaping) {
                value.append(ch);
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                return value.toString();
            } else {
                value.append(ch);
            }
        }
        return null;
    }

    private void recordRaw(String rawPayload, long receiveTsNs, Instant receiveWallTs) {
        try {
            rawRecorder.recordInbound(connectionId, rawPayload, receiveTsNs, receiveWallTs);
        } catch (Exception ignored) {
        }
    }

    private void recordRawDb(String rawPayload, long receiveTsNs, Instant receiveWallTs) {
        try {
            rawDbRecorder.recordInbound(rawPayload, receiveTsNs, receiveWallTs);
        } catch (Exception ignored) {
        }
    }

    private void handleAck(String rawPayload) {
        if (!ackCallbacks.shouldParseInboundAcks()) {
            return;
        }
        try {
            JSONObject data = (JSONObject) new JSONParser().parse(rawPayload);
            String type = (String) data.get("type");
            JSONObject msg = (JSONObject) data.get("msg");
            Long id = longValue(data.get("id"));
            if ("error".equals(type) && msg != null) {
                ackCallbacks.onError(id, longValue(msg.get("code")), (String) msg.get("msg"));
            } else if ("subscribed".equals(type) && msg != null) {
                ackCallbacks.onSubscribed(id, longValue(msg.get("sid")));
            } else if ("ok".equals(type)) {
                ackCallbacks.onOk(id);
            }
        } catch (Exception ignored) {
        }
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private record ClusterOfferMetricKey(String messageType, String result) {
    }

    @FunctionalInterface
    interface ClusterWriter {
        boolean write(byte[] payload);
    }

    @FunctionalInterface
    interface RawRecorder {
        void recordInbound(String connectionId, String rawPayload, long receiveTsNs, Instant receiveWallTs);

        static RawRecorder disabled() {
            return (connectionId, rawPayload, receiveTsNs, receiveWallTs) -> {
            };
        }
    }

    @FunctionalInterface
    interface RawDbRecorder {
        DbOfferResult recordInbound(String rawPayload, long receiveTsNs, Instant receiveWallTs);

        static RawDbRecorder disabled() {
            return (rawPayload, receiveTsNs, receiveWallTs) -> DbOfferResult.DISABLED;
        }
    }

    interface AckCallbacks {
        default boolean shouldParseInboundAcks() {
            return true;
        }

        void onError(Long id, Long code, String message);

        void onSubscribed(Long id, Long sid);

        void onOk(Long id);
    }
}
