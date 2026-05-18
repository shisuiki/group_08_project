package edu.illinois.group8.wrapper;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.ingress.KalshiIngressEnvelope;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.CanonicalDbEvent;
import edu.illinois.group8.storage.db.DbOfferResult;
import edu.illinois.group8.storage.db.DbWriterStats;
import edu.illinois.group8.storage.db.RawDbIngestSink;
import edu.illinois.group8.storage.db.RawWsDbEventInput;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KalshiInboundMessageHandlerTest {
    private static final long RECEIVE_TS_NS = 123_456_789L;
    private static final Instant RECEIVE_WALL_TS = Instant.parse("2026-05-19T00:00:00Z");
    private static final String CONNECTION_ID = "capture-1-1";

    @Test
    void validDataMessageWritesClusterBeforeSideChannels() {
        RecordingDeps deps = new RecordingDeps();
        KalshiInboundMessageHandler handler = deps.handler();
        String rawPayload = "{\"type\":\"ticker\",\"msg\":{\"market_ticker\":\"MARKET-1\"}}";

        handler.handleInbound(rawPayload, RECEIVE_TS_NS, RECEIVE_WALL_TS);

        assertEquals(List.of("cluster", "raw", "rawDb"), deps.order);
        KalshiIngressEnvelope envelope = KalshiIngressEnvelope.parse(deps.clusterPayloads.get(0), -1L);
        assertTrue(envelope.enveloped());
        assertEquals(rawPayload, envelope.rawPayload());
        assertEquals(RECEIVE_TS_NS, envelope.receiveTsNs());
        assertEquals(RECEIVE_WALL_TS.toString(), envelope.receiveWallTs());
        assertEquals(CONNECTION_ID, envelope.connectionId());
    }

    @Test
    void malformedJsonStillWritesClusterOnceAndRunsSideChannels() {
        RecordingDeps deps = new RecordingDeps();
        KalshiInboundMessageHandler handler = deps.handler();

        assertDoesNotThrow(() -> handler.handleInbound("{bad", RECEIVE_TS_NS, RECEIVE_WALL_TS));

        assertEquals(List.of("cluster", "raw", "rawDb"), deps.order);
        assertEquals(1, deps.clusterPayloads.size());
        assertEquals(0, deps.subscribedCallbacks.size());
        assertEquals(0, deps.okCallbacks.size());
        assertEquals(0, deps.errorCallbacks.size());
    }

    @Test
    void rawDbRecorderExceptionDoesNotEscapeOrBlockAckCallbacks() {
        RecordingDeps deps = new RecordingDeps();
        deps.throwRawDb = true;
        KalshiInboundMessageHandler handler = deps.handler();

        assertDoesNotThrow(() -> handler.handleInbound(
            "{\"type\":\"subscribed\",\"id\":17,\"msg\":{\"sid\":44}}",
            RECEIVE_TS_NS,
            RECEIVE_WALL_TS
        ));

        assertEquals(List.of("cluster", "raw", "rawDb", "subscribed"), deps.order);
        assertEquals(List.of(new SubscribedCallback(17L, 44L)), deps.subscribedCallbacks);
    }

    @Test
    void rawRecorderExceptionDoesNotEscapeOrBlockRawDbOrAckCallbacks() {
        RecordingDeps deps = new RecordingDeps();
        deps.throwRaw = true;
        KalshiInboundMessageHandler handler = deps.handler();

        assertDoesNotThrow(() -> handler.handleInbound(
            "{\"type\":\"ok\",\"id\":18}",
            RECEIVE_TS_NS,
            RECEIVE_WALL_TS
        ));

        assertEquals(List.of("cluster", "raw", "rawDb", "ok"), deps.order);
        assertEquals(List.of(18L), deps.okCallbacks);
    }

    @Test
    void rawDbConnectionAdapterOffersRawInputThroughRealSinkConnection() {
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        RawDbIngestSink sink = new RawDbIngestSink(writer, "kalshi-ws", "capture-1");
        RawDbIngestSink.RawDbIngestConnection connection = sink.newConnection();
        KalshiInboundMessageHandler.RawDbRecorder recorder = KalshiWebSocketClient.rawDbRecorderFor(connection);
        RecordingDeps deps = new RecordingDeps();
        KalshiInboundMessageHandler handler = deps.handler((rawPayload, receiveTsNs, receiveWallTs) -> {
            deps.order.add("rawDb");
            return recorder.recordInbound(rawPayload, receiveTsNs, receiveWallTs);
        });
        String rawPayload = "{\"type\":\"ticker\",\"msg\":{\"market_ticker\":\"MARKET-1\"}}";

        handler.handleInbound(rawPayload, RECEIVE_TS_NS, RECEIVE_WALL_TS);

        assertEquals(List.of("cluster", "raw", "rawDb"), deps.order);
        assertEquals(1, writer.rawInputs.size());
        RawWsDbEventInput input = writer.rawInputs.get(0);
        assertEquals("kalshi-ws", input.source());
        assertEquals("capture-1", input.captureId());
        assertEquals(connection.connectionId(), input.connectionId());
        assertEquals(1L, input.connectionSequence());
        assertEquals(RECEIVE_TS_NS, input.receiveTsNs());
        assertEquals(RECEIVE_WALL_TS, input.receiveWallTs());
        assertEquals(rawPayload, input.rawPayload());
        assertEquals("queued", input.ingestStatus());
    }

    @Test
    void nullRawDbConnectionAdapterReturnsDisabledRecorder() {
        KalshiInboundMessageHandler.RawDbRecorder recorder = KalshiWebSocketClient.rawDbRecorderFor(null);

        assertEquals(DbOfferResult.DISABLED, recorder.recordInbound("payload", RECEIVE_TS_NS, RECEIVE_WALL_TS));
    }

    @Test
    void subscribedAckCallbackRunsAfterClusterWrite() {
        RecordingDeps deps = new RecordingDeps();
        KalshiInboundMessageHandler handler = deps.handler();

        handler.handleInbound("{\"type\":\"subscribed\",\"id\":17,\"msg\":{\"sid\":44}}", RECEIVE_TS_NS, RECEIVE_WALL_TS);

        assertOrder(deps.order, "cluster", "subscribed");
        assertEquals(List.of(new SubscribedCallback(17L, 44L)), deps.subscribedCallbacks);
    }

    @Test
    void okAckCallbackRunsAfterClusterWrite() {
        RecordingDeps deps = new RecordingDeps();
        KalshiInboundMessageHandler handler = deps.handler();

        handler.handleInbound("{\"type\":\"ok\",\"id\":18}", RECEIVE_TS_NS, RECEIVE_WALL_TS);

        assertOrder(deps.order, "cluster", "ok");
        assertEquals(List.of(18L), deps.okCallbacks);
    }

    @Test
    void errorAckCallbackRunsAfterClusterWrite() {
        RecordingDeps deps = new RecordingDeps();
        KalshiInboundMessageHandler handler = deps.handler();

        handler.handleInbound(
            "{\"type\":\"error\",\"id\":19,\"msg\":{\"code\":400,\"msg\":\"bad request\"}}",
            RECEIVE_TS_NS,
            RECEIVE_WALL_TS
        );

        assertOrder(deps.order, "cluster", "error");
        assertEquals(List.of(new ErrorCallback(19L, 400L, "bad request")), deps.errorCallbacks);
    }

    private static void assertOrder(List<String> order, String before, String after) {
        assertTrue(order.indexOf(before) >= 0, before + " side effect should be present");
        assertTrue(order.indexOf(after) > order.indexOf(before), after + " should run after " + before);
    }

    private static final class RecordingDeps {
        private final List<String> order = new ArrayList<>();
        private final List<String> clusterPayloads = new ArrayList<>();
        private final List<SubscribedCallback> subscribedCallbacks = new ArrayList<>();
        private final List<Long> okCallbacks = new ArrayList<>();
        private final List<ErrorCallback> errorCallbacks = new ArrayList<>();
        private boolean throwRaw;
        private boolean throwRawDb;

        private KalshiInboundMessageHandler handler() {
            return handler((rawPayload, receiveTsNs, receiveWallTs) -> {
                order.add("rawDb");
                if (throwRawDb) {
                    throw new IllegalStateException("raw db failed");
                }
                return DbOfferResult.ACCEPTED;
            });
        }

        private KalshiInboundMessageHandler handler(KalshiInboundMessageHandler.RawDbRecorder rawDbRecorder) {
            return new KalshiInboundMessageHandler(
                payload -> {
                    order.add("cluster");
                    clusterPayloads.add(payload);
                    return true;
                },
                (connectionId, rawPayload, receiveTsNs, receiveWallTs) -> {
                    order.add("raw");
                    if (throwRaw) {
                        throw new IllegalStateException("raw recorder failed");
                    }
                    assertEquals(CONNECTION_ID, connectionId);
                    assertEquals(RECEIVE_TS_NS, receiveTsNs);
                    assertEquals(RECEIVE_WALL_TS, receiveWallTs);
                },
                rawDbRecorder,
                new KalshiInboundMessageHandler.AckCallbacks() {
                    @Override
                    public void onError(Long id, Long code, String message) {
                        order.add("error");
                        errorCallbacks.add(new ErrorCallback(id, code, message));
                    }

                    @Override
                    public void onSubscribed(Long id, Long sid) {
                        order.add("subscribed");
                        subscribedCallbacks.add(new SubscribedCallback(id, sid));
                    }

                    @Override
                    public void onOk(Long id) {
                        order.add("ok");
                        okCallbacks.add(id);
                    }
                },
                CONNECTION_ID
            );
        }
    }

    private static final class RecordingAsyncDbWriter implements AsyncDbWriter {
        private final List<RawWsDbEventInput> rawInputs = new ArrayList<>();

        @Override
        public DbOfferResult offerRaw(RawWsDbEventInput input) {
            rawInputs.add(input);
            return DbOfferResult.ACCEPTED;
        }

        @Override
        public DbOfferResult offerCanonical(CanonicalDbEvent event) {
            throw new UnsupportedOperationException("canonical writes are out of scope");
        }

        @Override
        public DbOfferResult offerCanonicalEvent(CanonicalEvent event) {
            throw new UnsupportedOperationException("canonical writes are out of scope");
        }

        @Override
        public DbWriterStats stats() {
            return DbWriterStats.empty();
        }

        @Override
        public void close() {
        }
    }

    private record SubscribedCallback(Long id, Long sid) {
    }

    private record ErrorCallback(Long id, Long code, String message) {
    }
}
