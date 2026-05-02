package edu.illinois.group8.parser;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.RawSourceEvent;

import java.util.List;

public record CanonicalParseResult(
    RawSourceEvent rawSourceEvent,
    List<CanonicalEvent> canonicalEvents
) {
    public CanonicalParseResult {
        canonicalEvents = List.copyOf(canonicalEvents);
    }
}
