package edu.illinois.group8.feature;

import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.metrics.BackendMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturePlantServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void runsFeatureModulesFromRecordedCanonicalSource() throws Exception {
        write("derived.top_of_book", """
            {"event_id":"b1","event_type":"top_of_book_update","schema_version":1,"stream_name":"derived.top_of_book","metadata":{"source":"kalshi","market_ticker":"M","event_ts_ms":1700000000000},"bid_price_micros":440000,"bid_quantity_micros":1000000,"ask_price_micros":470000,"ask_quantity_micros":2000000,"crossed":false}
            """);
        write("canonical.trade", """
            {"event_id":"t1","event_type":"market_trade","schema_version":1,"stream_name":"canonical.trade","metadata":{"source":"kalshi","market_ticker":"M","event_ts_ms":1700000000001},"trade_id":"trade-1","yes_price_micros":460000,"no_price_micros":540000,"quantity_micros":3000000,"taker_side":"yes"}
            """);

        RecordingCanonicalEnvelopeSource source = RecordingCanonicalEnvelopeSource.fromRoot(tempDir, List.of(
            StreamRegistry.byName("derived.top_of_book").orElseThrow(),
            StreamRegistry.byName("canonical.trade").orElseThrow()
        ), 0L);
        CollectingFeatureOutputSink sink = new CollectingFeatureOutputSink();
        BackendMetrics metrics = new BackendMetrics();

        try (FeaturePlantService service = new FeaturePlantService(
            source,
            List.of(new BestBidOfferFeatureModule(), new TradeTapeFeatureModule()),
            sink,
            metrics
        )) {
            assertEquals(2L, service.runUntilExhausted(10));
        }

        assertEquals(List.of("feature.bbo", "feature.trade_tape"), sink.outputs().stream().map(FeatureOutput::featureName).toList());
        assertEquals(30000L, sink.outputs().get(0).values().get("spread_micros"));
        assertEquals("trade-1", sink.outputs().get(1).values().get("trade_id"));
        assertEquals(1L, metrics.get(
            "feature_module_events_out_total",
            BackendMetrics.labels("service", "featureplant", "module", "feature.bbo", "stream", "derived.top_of_book")
        ));
    }

    @Test
    void boundedBufferKeepsMostRecentOutputs() {
        BoundedFeatureOutputBuffer buffer = new BoundedFeatureOutputBuffer(1);
        buffer.write(new FeatureOutput("feature.a", "feature.a", "M1", 1L, "e1", java.util.Map.of()));
        buffer.write(new FeatureOutput("feature.b", "feature.b", "M2", 2L, "e2", java.util.Map.of()));

        assertEquals(1, buffer.snapshot().size());
        assertEquals("feature.b", buffer.snapshot().get(0).featureName());
    }

    private void write(String streamName, String payload) throws Exception {
        Path file = tempDir
            .resolve("canonical")
            .resolve("stream=" + streamName)
            .resolve("date=2023-11-14")
            .resolve("hour=22")
            .resolve("events.ndjson");
        Files.createDirectories(file.getParent());
        Files.writeString(file, payload.trim() + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
    }
}
