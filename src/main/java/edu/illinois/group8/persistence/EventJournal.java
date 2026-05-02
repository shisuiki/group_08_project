package edu.illinois.group8.persistence;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.RawSourceEvent;

public interface EventJournal {
    boolean appendRaw(RawSourceEvent event);

    boolean appendCanonical(CanonicalEvent event);
}
