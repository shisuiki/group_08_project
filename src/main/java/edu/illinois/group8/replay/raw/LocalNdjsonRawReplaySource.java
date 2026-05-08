package edu.illinois.group8.replay.raw;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public class LocalNdjsonRawReplaySource implements RawReplaySource {
    private final Path root;
    private final RawRecordingReader reader;

    public LocalNdjsonRawReplaySource(Path root) {
        this.root = root;
        this.reader = new RawRecordingReader(root);
    }

    @Override
    public List<RawReplayEvent> read(RawReplaySelection selection) {
        return reader.read(0L).stream()
            .filter(event -> selection.startReceiveTsNs() == null
                || (event.receiveTsNs() != null && event.receiveTsNs() >= selection.startReceiveTsNs()))
            .filter(event -> selection.endReceiveTsNs() == null
                || (event.receiveTsNs() != null && event.receiveTsNs() <= selection.endReceiveTsNs()))
            .filter(event -> selection.marketTickers().isEmpty()
                || selection.marketTickers().contains(event.marketTicker()))
            .filter(event -> selection.rawEventIds().isEmpty()
                || selection.rawEventIds().contains(event.rawEventId()))
            .sorted(Comparator
                .comparing((RawReplayEvent event) -> event.receiveTsNs() == null ? Long.MAX_VALUE : event.receiveTsNs())
                .thenComparing(RawReplayEvent::sourceName)
                .thenComparing(RawReplayEvent::sourcePosition))
            .limit(selection.maxEvents() > 0L ? selection.maxEvents() : Long.MAX_VALUE)
            .toList();
    }

    @Override
    public String description() {
        return "local-ndjson:" + root;
    }
}
