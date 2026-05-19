package edu.illinois.group8.backfill;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.recorder.CanonicalRecordingWriter;

import java.util.List;
import java.util.Objects;

final class RecordingCanonicalBackfillSink implements CanonicalBackfillSink {
    private final CanonicalRecordingWriter writer;

    RecordingCanonicalBackfillSink(CanonicalRecordingWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public void writeBatch(List<CanonicalEvent> events, long receiveTsNs) throws Exception {
        for (CanonicalEvent event : events) {
            writer.writeEvent(event, receiveTsNs);
        }
    }
}
