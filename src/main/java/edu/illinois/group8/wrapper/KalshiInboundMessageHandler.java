package edu.illinois.group8.wrapper;

import edu.illinois.group8.ingress.KalshiIngressEnvelope;
import edu.illinois.group8.storage.db.DbOfferResult;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.time.Instant;
import java.util.Objects;

final class KalshiInboundMessageHandler {
    private final ClusterWriter clusterWriter;
    private final RawRecorder rawRecorder;
    private final RawDbRecorder rawDbRecorder;
    private final AckCallbacks ackCallbacks;
    private final String connectionId;

    KalshiInboundMessageHandler(
        ClusterWriter clusterWriter,
        RawRecorder rawRecorder,
        RawDbRecorder rawDbRecorder,
        AckCallbacks ackCallbacks,
        String connectionId
    ) {
        this.clusterWriter = Objects.requireNonNull(clusterWriter, "clusterWriter");
        this.rawRecorder = Objects.requireNonNull(rawRecorder, "rawRecorder");
        this.rawDbRecorder = Objects.requireNonNull(rawDbRecorder, "rawDbRecorder");
        this.ackCallbacks = Objects.requireNonNull(ackCallbacks, "ackCallbacks");
        this.connectionId = connectionId;
    }

    void handleInbound(String rawPayload, long receiveTsNs, Instant receiveWallTs) {
        String clusterPayload = KalshiIngressEnvelope.wrap(rawPayload, receiveTsNs, receiveWallTs, connectionId, null);
        clusterWriter.write(clusterPayload);
        recordRaw(rawPayload, receiveTsNs, receiveWallTs);
        recordRawDb(rawPayload, receiveTsNs, receiveWallTs);
        handleAck(rawPayload);
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

    @FunctionalInterface
    interface ClusterWriter {
        boolean write(String payload);
    }

    @FunctionalInterface
    interface RawRecorder {
        void recordInbound(String connectionId, String rawPayload, long receiveTsNs, Instant receiveWallTs);
    }

    @FunctionalInterface
    interface RawDbRecorder {
        DbOfferResult recordInbound(String rawPayload, long receiveTsNs, Instant receiveWallTs);

        static RawDbRecorder disabled() {
            return (rawPayload, receiveTsNs, receiveWallTs) -> DbOfferResult.DISABLED;
        }
    }

    interface AckCallbacks {
        void onError(Long id, Long code, String message);

        void onSubscribed(Long id, Long sid);

        void onOk(Long id);
    }
}
