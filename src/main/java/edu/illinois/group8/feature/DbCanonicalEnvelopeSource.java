package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.storage.db.CanonicalDbCursor;
import edu.illinois.group8.storage.db.CanonicalDbEventReader;
import edu.illinois.group8.storage.db.CanonicalDbReadEvent;
import edu.illinois.group8.storage.db.CanonicalDbReadRequest;
import edu.illinois.group8.storage.db.FeaturePlantCursorStore;

import java.util.List;
import java.util.Objects;

public class DbCanonicalEnvelopeSource implements CanonicalEnvelopeSource {
    public static final int DEFAULT_UNBOUNDED_POLL_LIMIT = 1000;

    private final CanonicalDbEventReader reader;
    private final List<String> streams;
    private final ObjectMapper mapper;
    private final long maxEvents;
    private final boolean includeReplayEvents;
    private final String replayId;
    private final FeaturePlantCursorStore cursorStore;
    private final String cursorName;

    private CanonicalDbCursor cursor;
    private long consumedEvents;

    public DbCanonicalEnvelopeSource(
        CanonicalDbEventReader reader,
        List<StreamContract> streams,
        long maxEvents,
        boolean includeReplayEvents,
        String replayId
    ) {
        this(reader, streamNames(streams), maxEvents, includeReplayEvents, replayId, new JsonCanonicalSerializer().mapper());
    }

    public DbCanonicalEnvelopeSource(
        CanonicalDbEventReader reader,
        List<StreamContract> streams,
        long maxEvents,
        boolean includeReplayEvents,
        String replayId,
        FeaturePlantCursorStore cursorStore,
        String cursorName
    ) {
        this(
            reader,
            streamNames(streams),
            maxEvents,
            includeReplayEvents,
            replayId,
            new JsonCanonicalSerializer().mapper(),
            cursorStore,
            cursorName
        );
    }

    DbCanonicalEnvelopeSource(
        CanonicalDbEventReader reader,
        List<String> streams,
        long maxEvents,
        boolean includeReplayEvents,
        String replayId,
        ObjectMapper mapper
    ) {
        this(reader, streams, maxEvents, includeReplayEvents, replayId, mapper, null, null);
    }

    DbCanonicalEnvelopeSource(
        CanonicalDbEventReader reader,
        List<String> streams,
        long maxEvents,
        boolean includeReplayEvents,
        String replayId,
        ObjectMapper mapper,
        FeaturePlantCursorStore cursorStore,
        String cursorName
    ) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.streams = List.copyOf(Objects.requireNonNull(streams, "streams"));
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.maxEvents = Math.max(0L, maxEvents);
        this.includeReplayEvents = includeReplayEvents;
        this.replayId = replayId == null ? "" : replayId.trim();
        this.cursorName = normalizeCursorName(cursorName);
        this.cursorStore = this.cursorName.isEmpty() ? null : Objects.requireNonNull(cursorStore, "cursorStore");
        this.cursor = this.cursorStore == null
            ? CanonicalDbCursor.start()
            : this.cursorStore.loadCursor(this.cursorName).orElse(CanonicalDbCursor.start());
    }

    @Override
    public int poll(CanonicalEnvelopeHandler handler, int fragmentLimit) {
        Objects.requireNonNull(handler, "handler");
        int readLimit = readLimit(fragmentLimit);
        if (readLimit <= 0) {
            return 0;
        }

        List<CanonicalDbReadEvent> events = reader.read(new CanonicalDbReadRequest(
            cursor,
            streams,
            List.of(),
            replayId,
            includeReplayEvents,
            readLimit
        ));

        int emitted = 0;
        for (CanonicalDbReadEvent event : events) {
            if (emitted >= readLimit) {
                break;
            }
            if (maxEvents > 0L && consumedEvents >= maxEvents) {
                break;
            }
            CanonicalEnvelope envelope = CanonicalEnvelope.fromPayload(event.streamName(), event.payload(), mapper);
            handler.onEvent(envelope);
            CanonicalDbCursor nextCursor = event.nextCursor();
            if (cursorStore != null) {
                cursorStore.saveCursor(cursorName, nextCursor);
            }
            cursor = nextCursor;
            consumedEvents++;
            emitted++;
        }
        return emitted;
    }

    CanonicalDbCursor cursor() {
        return cursor;
    }

    long consumedEvents() {
        return consumedEvents;
    }

    private int readLimit(int fragmentLimit) {
        long limit = fragmentLimit <= 0 ? DEFAULT_UNBOUNDED_POLL_LIMIT : fragmentLimit;
        if (maxEvents > 0L) {
            long remaining = maxEvents - consumedEvents;
            if (remaining <= 0L) {
                return 0;
            }
            limit = Math.min(limit, remaining);
        }
        return (int) Math.min(limit, Integer.MAX_VALUE);
    }

    private static List<String> streamNames(List<StreamContract> streams) {
        Objects.requireNonNull(streams, "streams");
        return streams.stream()
            .map(StreamContract::streamName)
            .toList();
    }

    private static String normalizeCursorName(String cursorName) {
        return cursorName == null ? "" : cursorName.trim();
    }
}
