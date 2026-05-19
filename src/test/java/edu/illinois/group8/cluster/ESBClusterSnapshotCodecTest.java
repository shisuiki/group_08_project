package edu.illinois.group8.cluster;

import edu.illinois.group8.book.OrderBookRecoveryCheckpoint;
import edu.illinois.group8.esb.DataProcessorRecoveryState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ESBClusterSnapshotCodecTest {
    @Test
    void roundTripsV1RecoveryState() {
        DataProcessorRecoveryState state = new DataProcessorRecoveryState(
            Map.of(11L, 4L, 12L, 8L),
            List.of(
                new OrderBookRecoveryCheckpoint("M", 2L),
                new OrderBookRecoveryCheckpoint("N", null)
            )
        );

        byte[] encoded = ESBClusterSnapshotCodec.encode(state);
        String json = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"version\":1"));
        assertTrue(json.contains("\"source_watermarks\""));
        assertTrue(json.contains("\"order_book_recovery_checkpoints\""));
        assertEquals(state, ESBClusterSnapshotCodec.decode(encoded));
    }

    @Test
    void rejectsMalformedOrUnknownSchema() {
        assertThrows(IllegalArgumentException.class, () ->
            ESBClusterSnapshotCodec.decode("not-json".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> decode("""
            {"version":2,"source_watermarks":[],"order_book_recovery_checkpoints":[]}
            """));
        assertThrows(IllegalArgumentException.class, () -> decode("""
            {"version":1,"source_watermarks":[]}
            """));
        assertThrows(IllegalArgumentException.class, () -> decode("""
            {"version":1,"source_watermarks":{},"order_book_recovery_checkpoints":[]}
            """));
        assertThrows(IllegalArgumentException.class, () -> decode("""
            {"version":1,"source_watermarks":[{"subscription_id":11,"sequence":null}],"order_book_recovery_checkpoints":[]}
            """));
        assertThrows(IllegalArgumentException.class, () -> decode("""
            {"version":1,"source_watermarks":[{"subscription_id":11,"sequence":4},{"subscription_id":11,"sequence":5}],"order_book_recovery_checkpoints":[]}
            """));
        assertThrows(IllegalArgumentException.class, () -> decode("""
            {"version":1,"source_watermarks":[{"subscription_id":-1,"sequence":4}],"order_book_recovery_checkpoints":[]}
            """));
        assertThrows(IllegalArgumentException.class, () -> decode("""
            {"version":1,"source_watermarks":[],"order_book_recovery_checkpoints":[{"market_ticker":" ","last_sequence":2}]}
            """));
        assertThrows(IllegalArgumentException.class, () -> decode("""
            {"version":1,"source_watermarks":[],"order_book_recovery_checkpoints":[{"market_ticker":"M","last_sequence":-1}]}
            """));
        assertThrows(IllegalArgumentException.class, () -> decode("""
            {"version":1,"source_watermarks":[],"order_book_recovery_checkpoints":[{"market_ticker":"M","last_sequence":1},{"market_ticker":"M","last_sequence":2}]}
            """));
        assertThrows(IllegalArgumentException.class, () -> decode("""
            {"version":1,"source_watermarks":[],"order_book_recovery_checkpoints":[],"extra":true}
            """));
    }

    @Test
    void emptyStateRoundTrips() {
        DataProcessorRecoveryState empty = new DataProcessorRecoveryState(Map.of(), List.of());

        byte[] encoded = ESBClusterSnapshotCodec.encode(empty);

        assertEquals(empty, ESBClusterSnapshotCodec.decode(encoded));
    }

    private static DataProcessorRecoveryState decode(String json) {
        return ESBClusterSnapshotCodec.decode(json.getBytes(StandardCharsets.UTF_8));
    }
}
