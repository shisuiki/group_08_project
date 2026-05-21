package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AeronCanonicalEnvelopeSourceTest {
    private static final ObjectMapper MAPPER = new JsonCanonicalSerializer().mapper();
    private static final StreamContract TRADE = StreamRegistry.byName("canonical.trade").orElseThrow();
    private static final StreamContract TOP_OF_BOOK = StreamRegistry.byName("derived.top_of_book").orElseThrow();

    @Test
    void repeatedSingleFragmentPollsAdvanceAcrossStreams() {
        FakePoller tradePoller = new FakePoller(
            payload("trade-1", TRADE.streamName()),
            payload("trade-2", TRADE.streamName())
        );
        FakePoller topPoller = new FakePoller(
            payload("top-1", TOP_OF_BOOK.streamName()),
            payload("top-2", TOP_OF_BOOK.streamName())
        );
        AeronCanonicalEnvelopeSource source = source(List.of(TRADE, TOP_OF_BOOK), tradePoller, topPoller);
        List<String> delivered = new ArrayList<>();

        assertEquals(1, source.poll(envelope -> delivered.add(envelope.streamName() + ":" + envelope.eventId()), 1));
        assertEquals(1, source.poll(envelope -> delivered.add(envelope.streamName() + ":" + envelope.eventId()), 1));
        assertEquals(1, source.poll(envelope -> delivered.add(envelope.streamName() + ":" + envelope.eventId()), 1));
        assertEquals(1, source.poll(envelope -> delivered.add(envelope.streamName() + ":" + envelope.eventId()), 1));

        assertEquals(List.of(
            "canonical.trade:trade-1",
            "derived.top_of_book:top-1",
            "canonical.trade:trade-2",
            "derived.top_of_book:top-2"
        ), delivered);
    }

    @Test
    void liveAeronPollMarksConsumerReceiveTimestamp() {
        FakePoller tradePoller = new FakePoller(payload("trade-1", TRADE.streamName()));
        AeronCanonicalEnvelopeSource source = source(List.of(TRADE), tradePoller);
        List<Long> receiveTsNs = new ArrayList<>();

        assertEquals(1, source.poll(envelope -> receiveTsNs.add(envelope.consumerReceiveTsNs()), 1));

        assertEquals(1, receiveTsNs.size());
        org.junit.jupiter.api.Assertions.assertNotNull(receiveTsNs.get(0));
        org.junit.jupiter.api.Assertions.assertTrue(receiveTsNs.get(0) > 0L);
    }

    @Test
    void emptyFirstPollerDoesNotBlockLaterStream() {
        FakePoller emptyPoller = new FakePoller();
        FakePoller topPoller = new FakePoller(payload("top-1", TOP_OF_BOOK.streamName()));
        AeronCanonicalEnvelopeSource source = source(List.of(TRADE, TOP_OF_BOOK), emptyPoller, topPoller);
        List<String> delivered = new ArrayList<>();

        assertEquals(1, source.poll(envelope -> delivered.add(envelope.streamName()), 1));

        assertEquals(List.of("derived.top_of_book"), delivered);
        assertEquals(1, emptyPoller.pollCalls);
        assertEquals(1, topPoller.pollCalls);
    }

    @Test
    void closeClosesPollersAndResourcesOnce() {
        FakePoller tradePoller = new FakePoller();
        FakePoller topPoller = new FakePoller();
        AtomicInteger resourceCloses = new AtomicInteger();
        AeronCanonicalEnvelopeSource source = new AeronCanonicalEnvelopeSource(
            "test-channel",
            List.of(TRADE, TOP_OF_BOOK),
            MAPPER,
            List.of(tradePoller, topPoller),
            resourceCloses::incrementAndGet
        );

        source.close();
        source.close();

        assertEquals(1, tradePoller.closeCalls);
        assertEquals(1, topPoller.closeCalls);
        assertEquals(1, resourceCloses.get());
    }

    private static AeronCanonicalEnvelopeSource source(
        List<StreamContract> streams,
        AeronCanonicalEnvelopeSource.StreamPoller... pollers
    ) {
        return new AeronCanonicalEnvelopeSource(
            "test-channel",
            streams,
            MAPPER,
            List.of(pollers),
            () -> {
            }
        );
    }

    private static String payload(String eventId, String streamName) {
        return """
            {"event_id":"%s","event_type":"test_event","schema_version":1,"stream_name":"%s","metadata":{"source":"kalshi","market_ticker":"M","event_ts_ms":1700000000000}}
            """.formatted(eventId, streamName).trim();
    }

    private static final class FakePoller implements AeronCanonicalEnvelopeSource.StreamPoller {
        private final Queue<byte[]> payloads = new ArrayDeque<>();
        private int pollCalls;
        private int closeCalls;

        private FakePoller(String... payloads) {
            for (String payload : payloads) {
                this.payloads.add(payload.getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public int poll(FragmentHandler handler, int fragmentLimit) {
            pollCalls++;
            int fragments = 0;
            while (fragments < fragmentLimit && !payloads.isEmpty()) {
                byte[] payload = payloads.remove();
                handler.onFragment(new UnsafeBuffer(payload), 0, payload.length, null);
                fragments++;
            }
            return fragments;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
