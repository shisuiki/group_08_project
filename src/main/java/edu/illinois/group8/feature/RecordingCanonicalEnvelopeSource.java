package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.replay.recording.RecordingEvent;
import edu.illinois.group8.replay.recording.RecordingEventReader;

import java.nio.file.Path;
import java.util.List;

public class RecordingCanonicalEnvelopeSource implements CanonicalEnvelopeSource {
    private final List<RecordingEvent> events;
    private final ObjectMapper mapper;
    private int cursor;

    public RecordingCanonicalEnvelopeSource(RecordingEventReader reader, List<StreamContract> streams, long maxEvents) {
        this(reader.read(streams, maxEvents), new JsonCanonicalSerializer().mapper());
    }

    public RecordingCanonicalEnvelopeSource(List<RecordingEvent> events, ObjectMapper mapper) {
        this.events = List.copyOf(events);
        this.mapper = mapper;
    }

    public static RecordingCanonicalEnvelopeSource fromRoot(Path root, List<StreamContract> streams, long maxEvents) {
        return new RecordingCanonicalEnvelopeSource(new RecordingEventReader(root), streams, maxEvents);
    }

    @Override
    public int poll(CanonicalEnvelopeHandler handler, int fragmentLimit) {
        int limit = fragmentLimit <= 0 ? Integer.MAX_VALUE : fragmentLimit;
        int emitted = 0;
        while (cursor < events.size() && emitted < limit) {
            handler.onEvent(CanonicalEnvelope.fromRecording(events.get(cursor++), mapper));
            emitted++;
        }
        return emitted;
    }

    public int remainingEvents() {
        return events.size() - cursor;
    }

    public void reset() {
        cursor = 0;
    }
}
