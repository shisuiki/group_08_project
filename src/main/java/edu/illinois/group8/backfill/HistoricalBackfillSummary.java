package edu.illinois.group8.backfill;

public record HistoricalBackfillSummary(
    long restResponsesFetched,
    long rawResponsesRecorded,
    long canonicalEventsParsed,
    long canonicalEventsRecorded,
    long marketsDiscovered,
    long failures
) {
}
