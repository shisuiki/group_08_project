package edu.illinois.group8.persistence;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.RawSourceEvent;

public class NoopEventJournal implements EventJournal {
    @Override
    public boolean appendRaw(RawSourceEvent event) {
        return true;
    }

    @Override
    public boolean appendCanonical(CanonicalEvent event) {
        return true;
    }
}
