package edu.illinois.group8.feature;

import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.CanonicalDbEventReader;
import edu.illinois.group8.storage.db.CanonicalDbReadEvent;
import edu.illinois.group8.storage.db.CanonicalDbReadRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void runsFeatureModulesFromDbCanonicalSource() {
        DbCanonicalEnvelopeSource source = new DbCanonicalEnvelopeSource(
            new OneShotDbReader(List.of(new CanonicalDbReadEvent(
                1L,
                "b1",
                null,
                null,
                "derived.top_of_book",
                "top_of_book_update",
                1,
                "M",
                1700000000000L,
                123L,
                456L,
                """
                    {"event_id":"b1","event_type":"top_of_book_update","schema_version":1,"stream_name":"derived.top_of_book","metadata":{"source":"kalshi","market_ticker":"M","event_ts_ms":1700000000000},"bid_price_micros":440000,"bid_quantity_micros":1000000,"ask_price_micros":470000,"ask_quantity_micros":2000000,"crossed":false}
                    """.trim()
            ))),
            List.of(StreamRegistry.byName("derived.top_of_book").orElseThrow()),
            0L,
            false,
            ""
        );
        CollectingFeatureOutputSink sink = new CollectingFeatureOutputSink();

        try (FeaturePlantService service = new FeaturePlantService(
            source,
            List.of(new BestBidOfferFeatureModule()),
            sink
        )) {
            assertEquals(1L, service.runUntilExhausted(10));
        }

        assertEquals(List.of("feature.bbo"), sink.outputs().stream().map(FeatureOutput::featureName).toList());
        assertEquals(30000L, sink.outputs().get(0).values().get("spread_micros"));
    }

    @Test
    void moduleSamplerSamplesFirstDispatchAndEverySixtyFourthAfter() {
        assertTrue(FeaturePlantService.shouldSampleHotPathDistribution(0L));
        for (long cursor = 1L; cursor < 64L; cursor++) {
            assertFalse(FeaturePlantService.shouldSampleHotPathDistribution(cursor));
        }
        assertTrue(FeaturePlantService.shouldSampleHotPathDistribution(64L));
        assertFalse(FeaturePlantService.shouldSampleHotPathDistribution(65L));
    }

    @Test
    void sampledModuleDistributionsKeepCountersExact() {
        List<CanonicalEnvelope> envelopes = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            envelopes.add(new CanonicalEnvelope(
                "canonical.test",
                "{}",
                com.fasterxml.jackson.databind.node.NullNode.getInstance(),
                1L,
                null
            ));
        }
        BackendMetrics metrics = new BackendMetrics();
        CollectingFeatureOutputSink sink = new CollectingFeatureOutputSink();

        try (FeaturePlantService service = new FeaturePlantService(
            new ListCanonicalEnvelopeSource(envelopes),
            List.of(new EmittingFeatureModule("feature.test", "canonical.test")),
            sink,
            metrics
        )) {
            assertEquals(65L, service.runUntilExhausted(10));
        }

        assertEquals(65, sink.outputs().size());
        var labels = BackendMetrics.labels("service", "featureplant", "module", "feature.test", "stream", "canonical.test");
        assertEquals(65L, metrics.get("feature_module_events_in_total", labels));
        assertEquals(65L, metrics.get("feature_module_events_out_total", labels));
        assertEquals(0L, metrics.get("feature_module_errors_total", labels));

        String text = metrics.prometheusText();
        assertTrue(text.contains(
            "feature_module_latency_ns_count{module=\"feature.test\",service=\"featureplant\",stream=\"canonical.test\"} 2\n"
        ));
        assertTrue(text.contains(
            "feature_module_lag_ms_count{module=\"feature.test\",service=\"featureplant\",stream=\"canonical.test\"} 2\n"
        ));
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

    private static final class OneShotDbReader implements CanonicalDbEventReader {
        private final List<CanonicalDbReadEvent> events;
        private boolean consumed;

        private OneShotDbReader(List<CanonicalDbReadEvent> events) {
            this.events = List.copyOf(events);
        }

        @Override
        public List<CanonicalDbReadEvent> read(CanonicalDbReadRequest request) {
            if (consumed) {
                return List.of();
            }
            consumed = true;
            return events;
        }
    }

    private static final class ListCanonicalEnvelopeSource implements CanonicalEnvelopeSource {
        private final List<CanonicalEnvelope> envelopes;
        private int cursor;

        private ListCanonicalEnvelopeSource(List<CanonicalEnvelope> envelopes) {
            this.envelopes = List.copyOf(envelopes);
        }

        @Override
        public int poll(CanonicalEnvelopeHandler handler, int fragmentLimit) {
            int dispatched = 0;
            while (dispatched < fragmentLimit && cursor < envelopes.size()) {
                handler.onEvent(envelopes.get(cursor++));
                dispatched++;
            }
            return dispatched;
        }
    }

    private static final class EmittingFeatureModule implements FeatureModule {
        private final String name;
        private final String streamName;

        private EmittingFeatureModule(String name, String streamName) {
            this.name = name;
            this.streamName = streamName;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> inputStreams() {
            return Set.of(streamName);
        }

        @Override
        public void onEvent(CanonicalEnvelope envelope, FeatureOutputCollector collector) {
            collector.emit(new FeatureOutput(
                name,
                streamName,
                "M",
                envelope.eventTsMs(),
                envelope.eventId(),
                Map.of("value", 1L)
            ));
        }
    }
}
