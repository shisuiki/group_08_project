package edu.illinois.group8.profile;

import java.util.Arrays;

public final class LatencyStats {
    private final long[] values;
    private int size;

    public LatencyStats(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.values = new long[capacity];
    }

    public void record(long value) {
        if (size >= values.length) {
            throw new IllegalStateException("latency recorder capacity exceeded");
        }
        values[size++] = value;
    }

    public Summary summarize() {
        if (size == 0) {
            return new Summary(0, 0, 0, 0, 0, 0, 0, 0);
        }
        long[] sorted = Arrays.copyOf(values, size);
        Arrays.sort(sorted);
        long sum = 0L;
        for (long value : sorted) {
            sum += value;
        }
        return new Summary(
            size,
            sorted[0],
            percentile(sorted, 50.0),
            percentile(sorted, 90.0),
            percentile(sorted, 95.0),
            percentile(sorted, 99.0),
            sorted[sorted.length - 1],
            sum / size
        );
    }

    private static long percentile(long[] sorted, double percentile) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = (percentile / 100.0) * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = rank - lower;
        return Math.round(sorted[lower] + ((sorted[upper] - sorted[lower]) * fraction));
    }

    public record Summary(
        int count,
        long minNs,
        long p50Ns,
        long p90Ns,
        long p95Ns,
        long p99Ns,
        long maxNs,
        long meanNs
    ) {
    }
}
