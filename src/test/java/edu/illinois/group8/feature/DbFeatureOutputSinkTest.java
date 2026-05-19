package edu.illinois.group8.feature;

import edu.illinois.group8.storage.db.FeatureOutputDbEvent;
import edu.illinois.group8.storage.db.FeatureOutputStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbFeatureOutputSinkTest {
    @Test
    void writesMappedEventToStore() {
        CapturingStore store = new CapturingStore();
        DbFeatureOutputSink sink = new DbFeatureOutputSink(store);

        sink.write(new FeatureOutput(
            "feature.bbo",
            "feature.bbo",
            "M1",
            100L,
            "source-1",
            Map.of("midpoint_micros", 123L)
        ));

        assertEquals(1, store.insertCalls);
        assertEquals("feature.bbo", store.output.featureName());
        assertEquals(FeatureOutputDbEventMapper.FEATURE_VERSION, store.output.featureVersion());
        assertEquals("source-1", store.output.sourceEventId());
        assertEquals("M1", store.output.marketTicker());
        assertEquals(100L, store.output.eventTsMs());
        assertEquals("{\"midpoint_micros\":123}", store.output.values());
    }

    @Test
    void wrapsStoreFailureWithFeatureName() {
        FailingStore store = new FailingStore();
        DbFeatureOutputSink sink = new DbFeatureOutputSink(store);
        FeatureOutput output = new FeatureOutput(
            "feature.trade_tape",
            "feature.trade_tape",
            "M1",
            100L,
            "source-1",
            Map.of("price", 42)
        );

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> sink.write(output));

        assertTrue(thrown.getMessage().contains("feature.trade_tape"));
        assertSame(store.failure, thrown.getCause());
    }

    private static final class CapturingStore implements FeatureOutputStore {
        private int insertCalls;
        private FeatureOutputDbEvent output;

        @Override
        public void insertFeatureOutput(FeatureOutputDbEvent output) {
            insertCalls++;
            this.output = output;
        }
    }

    private static final class FailingStore implements FeatureOutputStore {
        private final Exception failure = new Exception("db unavailable");

        @Override
        public void insertFeatureOutput(FeatureOutputDbEvent output) throws Exception {
            throw failure;
        }
    }
}
