package edu.illinois.group8.frontend;

public record Bar(
    long openTimeMs,
    long closeTimeMs,
    double open,
    double high,
    double low,
    double close,
    long sampleCount
) {
}
