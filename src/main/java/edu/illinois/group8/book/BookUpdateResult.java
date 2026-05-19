package edu.illinois.group8.book;

import edu.illinois.group8.canonical.CanonicalEvent;

import java.util.List;

public record BookUpdateResult(List<CanonicalEvent> generatedEvents) {
    private static final BookUpdateResult EMPTY = new BookUpdateResult(List.of());

    public BookUpdateResult {
        generatedEvents = List.copyOf(generatedEvents);
    }

    public static BookUpdateResult empty() {
        return EMPTY;
    }

    public static BookUpdateResult single(CanonicalEvent event) {
        return new BookUpdateResult(List.of(event));
    }

    public static BookUpdateResult of(CanonicalEvent first, CanonicalEvent second) {
        return new BookUpdateResult(List.of(first, second));
    }
}
