package edu.illinois.group8.feature;

import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.storage.db.CanonicalDbEventReader;
import edu.illinois.group8.storage.db.CanonicalDbReadEvent;
import edu.illinois.group8.storage.db.CanonicalDbReadRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbCanonicalEnvelopeSourceTest {
    @Test
    void pollReadsAtMostFragmentLimitAndAdvancesCursor() {
        FakeReader reader = new FakeReader(
            List.of(
                event(7L, "canonical.trade", "event-7"),
                event(8L, "canonical.ticker", "event-8")
            ),
            List.of()
        );
        DbCanonicalEnvelopeSource source = newSource(reader, 0L, false, "");
        List<String> eventIds = new ArrayList<>();

        assertEquals(2, source.poll(envelope -> eventIds.add(envelope.eventId()), 2));
        assertEquals(0, source.poll(envelope -> eventIds.add(envelope.eventId()), 2));

        assertEquals(List.of("event-7", "event-8"), eventIds);
        assertEquals(2, reader.requests.size());
        CanonicalDbReadRequest firstRequest = reader.requests.get(0);
        assertEquals(0L, firstRequest.cursor().lastCommitSeq());
        assertEquals(List.of("canonical.trade", "canonical.ticker"), firstRequest.streams());
        assertEquals(2, firstRequest.maxEvents());
        assertEquals(8L, reader.requests.get(1).cursor().lastCommitSeq());
        assertEquals(8L, source.cursor().lastCommitSeq());
    }

    @Test
    void pollHonorsMaxEventsAcrossMultiplePolls() {
        FakeReader reader = new FakeReader(
            List.of(event(1L, "canonical.trade", "event-1"), event(2L, "canonical.trade", "event-2")),
            List.of(event(3L, "canonical.trade", "event-3"))
        );
        DbCanonicalEnvelopeSource source = newSource(reader, 3L, false, "");

        assertEquals(2, source.poll(envelope -> {
        }, 2));
        assertEquals(1, source.poll(envelope -> {
        }, 2));
        assertEquals(0, source.poll(envelope -> {
        }, 2));

        assertEquals(3L, source.consumedEvents());
        assertEquals(2, reader.requests.size());
        assertEquals(2, reader.requests.get(0).maxEvents());
        assertEquals(1, reader.requests.get(1).maxEvents());
    }

    @Test
    void pollDefaultsToExcludeReplayRows() {
        FakeReader reader = new FakeReader(List.of());
        DbCanonicalEnvelopeSource source = newSource(reader, 0L, false, "");

        assertEquals(0, source.poll(envelope -> {
        }, 10));

        CanonicalDbReadRequest request = reader.requests.get(0);
        assertEquals(false, request.includeReplayEvents());
        assertEquals("", request.replayId());
    }

    @Test
    void pollCanIncludeReplayRows() {
        FakeReader reader = new FakeReader(List.of());
        DbCanonicalEnvelopeSource source = newSource(reader, 0L, true, "");

        assertEquals(0, source.poll(envelope -> {
        }, 10));

        assertEquals(true, reader.requests.get(0).includeReplayEvents());
        assertEquals("", reader.requests.get(0).replayId());
    }

    @Test
    void pollCanFilterSpecificReplayId() {
        FakeReader reader = new FakeReader(List.of());
        DbCanonicalEnvelopeSource source = newSource(reader, 0L, false, "replay-1");

        assertEquals(0, source.poll(envelope -> {
        }, 10));

        assertEquals(false, reader.requests.get(0).includeReplayEvents());
        assertEquals("replay-1", reader.requests.get(0).replayId());
    }

    @Test
    void nonPositiveFragmentLimitUsesBoundedReadLimit() {
        FakeReader reader = new FakeReader(List.of());
        DbCanonicalEnvelopeSource source = newSource(reader, 0L, false, "");

        assertEquals(0, source.poll(envelope -> {
        }, 0));

        assertEquals(DbCanonicalEnvelopeSource.DEFAULT_UNBOUNDED_POLL_LIMIT, reader.requests.get(0).maxEvents());
    }

    @Test
    void pollDoesNotEmitMoreThanRequestedEvenIfReaderReturnsExtraRows() {
        FakeReader reader = new FakeReader(List.of(
            event(1L, "canonical.trade", "event-1"),
            event(2L, "canonical.trade", "event-2")
        ));
        DbCanonicalEnvelopeSource source = newSource(reader, 0L, false, "");
        List<String> eventIds = new ArrayList<>();

        assertEquals(1, source.poll(envelope -> eventIds.add(envelope.eventId()), 1));

        assertEquals(List.of("event-1"), eventIds);
        assertEquals(1L, source.cursor().lastCommitSeq());
    }

    private static DbCanonicalEnvelopeSource newSource(
        FakeReader reader,
        long maxEvents,
        boolean includeReplayEvents,
        String replayId
    ) {
        return new DbCanonicalEnvelopeSource(
            reader,
            List.of(
                StreamRegistry.byName("canonical.trade").orElseThrow(),
                StreamRegistry.byName("canonical.ticker").orElseThrow()
            ),
            maxEvents,
            includeReplayEvents,
            replayId
        );
    }

    private static CanonicalDbReadEvent event(long commitSeq, String streamName, String eventId) {
        return new CanonicalDbReadEvent(
            commitSeq,
            eventId,
            null,
            null,
            streamName,
            "market_trade",
            1,
            "MARKET-1",
            1700000000000L,
            123L,
            456L,
            """
                {"event_id":"%s","event_type":"market_trade","schema_version":1,"stream_name":"%s","metadata":{"source":"kalshi","market_ticker":"MARKET-1","event_ts_ms":1700000000000}}
                """.formatted(eventId, streamName).trim()
        );
    }

    private static final class FakeReader implements CanonicalDbEventReader {
        private final Queue<List<CanonicalDbReadEvent>> responses = new ArrayDeque<>();
        private final List<CanonicalDbReadRequest> requests = new ArrayList<>();

        @SafeVarargs
        private FakeReader(List<CanonicalDbReadEvent>... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public List<CanonicalDbReadEvent> read(CanonicalDbReadRequest request) {
            requests.add(request);
            return responses.isEmpty() ? List.of() : responses.remove();
        }
    }
}
