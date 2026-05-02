package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;

import java.util.List;

public record BookUpdateResult(List<CanonicalEvent> generatedEvents) {
    public BookUpdateResult {
        generatedEvents = List.copyOf(generatedEvents);
    }

    public static BookUpdateResult empty() {
        return new BookUpdateResult(List.of());
    }
}
