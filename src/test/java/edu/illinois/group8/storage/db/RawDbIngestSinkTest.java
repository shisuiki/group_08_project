package edu.illinois.group8.storage.db;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RawDbIngestSinkTest {
    private static final Instant RECEIVE_WALL_TS = Instant.parse("2026-05-19T00:00:00Z");

    @Test
    void malformedPayloadPassesThroughToWriter() {
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        RawDbIngestSink sink = new RawDbIngestSink(writer, "kalshi-ws", "capture-1");

        DbOfferResult result = sink.newConnection().recordInbound("{bad", 123L, RECEIVE_WALL_TS);

        assertEquals(DbOfferResult.ACCEPTED, result);
        assertEquals(1, writer.rawInputs.size());
        assertEquals("{bad", writer.rawInputs.get(0).rawPayload());
    }

    @Test
    void recordsSourceCaptureConnectionSequenceTimestampsAndStatus() {
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        RawDbIngestSink sink = new RawDbIngestSink(writer, "kalshi-ws", "capture-1");
        RawDbIngestSink.RawDbIngestConnection connection = sink.newConnection();

        connection.recordInbound("payload", 456L, RECEIVE_WALL_TS);

        RawWsDbEventInput input = writer.rawInputs.get(0);
        assertEquals("kalshi-ws", input.source());
        assertEquals("capture-1", input.captureId());
        assertEquals("capture-1-1", input.connectionId());
        assertEquals(connection.connectionId(), input.connectionId());
        assertEquals(1L, input.connectionSequence());
        assertEquals(456L, input.receiveTsNs());
        assertEquals(RECEIVE_WALL_TS, input.receiveWallTs());
        assertEquals("payload", input.rawPayload());
        assertEquals("queued", input.ingestStatus());
    }

    @Test
    void sameConnectionSequenceIncrements() {
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        RawDbIngestSink.RawDbIngestConnection connection =
            new RawDbIngestSink(writer, "kalshi-ws", "capture-1").newConnection();

        connection.recordInbound("first", 1L, RECEIVE_WALL_TS);
        connection.recordInbound("second", 2L, RECEIVE_WALL_TS);

        assertEquals(List.of(1L, 2L), writer.rawInputs.stream().map(RawWsDbEventInput::connectionSequence).toList());
    }

    @Test
    void differentConnectionsHaveDistinctIdsAndIndependentSequences() {
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        RawDbIngestSink sink = new RawDbIngestSink(writer, "kalshi-ws", "capture-1");
        RawDbIngestSink.RawDbIngestConnection first = sink.newConnection();
        RawDbIngestSink.RawDbIngestConnection second = sink.newConnection();

        first.recordInbound("first", 1L, RECEIVE_WALL_TS);
        second.recordInbound("second", 2L, RECEIVE_WALL_TS);

        assertNotEquals(first.connectionId(), second.connectionId());
        assertEquals("capture-1-1", first.connectionId());
        assertEquals("capture-1-2", second.connectionId());
        assertEquals(List.of(1L, 1L), writer.rawInputs.stream().map(RawWsDbEventInput::connectionSequence).toList());
    }

    @Test
    void disabledWriterResultIsReturnedWithoutThrowing() {
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        writer.rawResult = DbOfferResult.DISABLED;
        RawDbIngestSink sink = new RawDbIngestSink(writer, "kalshi-ws", "capture-1");

        assertDoesNotThrow(() ->
            assertEquals(DbOfferResult.DISABLED, sink.newConnection().recordInbound("payload", 1L, RECEIVE_WALL_TS))
        );
    }

    @Test
    void closeClosesOwnedWriterOnce() {
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();
        RawDbIngestSink sink = new RawDbIngestSink(writer, "kalshi-ws", "capture-1");

        sink.close();
        sink.close();

        assertEquals(1, writer.closeCalls);
    }

    @Test
    void constructorRejectsRequiredNulls() {
        RecordingAsyncDbWriter writer = new RecordingAsyncDbWriter();

        assertThrows(NullPointerException.class, () -> new RawDbIngestSink(null, "source", "capture"));
        assertThrows(NullPointerException.class, () -> new RawDbIngestSink(writer, null, "capture"));
        assertThrows(NullPointerException.class, () -> new RawDbIngestSink(writer, "source", null));
    }

    private static final class RecordingAsyncDbWriter implements AsyncDbWriter {
        private final List<RawWsDbEventInput> rawInputs = new ArrayList<>();
        private DbOfferResult rawResult = DbOfferResult.ACCEPTED;
        private int closeCalls;

        @Override
        public DbOfferResult offerRaw(RawWsDbEventInput input) {
            rawInputs.add(input);
            return rawResult;
        }

        @Override
        public DbOfferResult offerCanonical(CanonicalDbEvent event) {
            throw new UnsupportedOperationException("canonical writes are out of scope");
        }

        @Override
        public DbWriterStats stats() {
            return DbWriterStats.empty();
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
